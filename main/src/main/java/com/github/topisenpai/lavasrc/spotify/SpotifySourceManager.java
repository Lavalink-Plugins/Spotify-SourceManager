package com.github.topisenpai.lavasrc.spotify;

import com.github.topisenpai.lavasrc.mirror.MirroringAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class SpotifySourceManager extends MirroringAudioSourceManager implements HttpConfigurable {

    public static final Pattern URL_PATTERN = Pattern.compile("(https?://)?(www\\.)?open\\.spotify\\.com/(user/[a-zA-Z0-9-_]+/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");
    public static final String SEARCH_PREFIX = "spsearch:";
    public static final String RECOMMENDATIONS_PREFIX = "sprec:";
    public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
    public static final int ALBUM_MAX_PAGE_ITEMS = 50;

    private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

    private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();

    private final String clientId;
    private final String clientSecret;

    private String token;
    private Instant tokenExpire;

    public SpotifySourceManager(String[] providers, String clientId, String clientSecret, AudioPlayerManager audioPlayerManager) {
        super(providers, audioPlayerManager);

        if (clientId == null || clientId.isEmpty()) {
            throw new IllegalArgumentException("Spotify client id must be set");
        }
        this.clientId = clientId;

        if (clientSecret == null || clientSecret.isEmpty()) {
            throw new IllegalArgumentException("Spotify secret must be set");
        }
        this.clientSecret = clientSecret;
    }

    @Override
    public String getSourceName() {
        return "spotify";
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        return new SpotifyAudioTrack(trackInfo,
                DataFormatTools.readNullableText(input),
                DataFormatTools.readNullableText(input),
                this
        );
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        try {
            if (reference.identifier.startsWith(SEARCH_PREFIX)) {
                return this.getSearch(reference.identifier.substring(SEARCH_PREFIX.length()).trim());
            }

            if (reference.identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
                return this.getRecommendations(reference.identifier.substring(RECOMMENDATIONS_PREFIX.length()).trim());
            }

            var matcher = URL_PATTERN.matcher(reference.identifier);
            if (!matcher.find()) {
                return null;
            }

            var id = matcher.group("identifier");
            switch (matcher.group("type")) {
                case "album":
                    return this.getAlbum(id);

                case "track":
                    return this.getTrack(id);

                case "playlist":
                    return this.getPlaylist(id);

                case "artist":
                    return this.getArtist(id);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public void requestToken() throws IOException {
        var request = new HttpPost("https://accounts.spotify.com/api/token");
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((this.clientId + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8)));
        request.setEntity(new UrlEncodedFormEntity(List.of(new BasicNameValuePair("grant_type", "client_credentials")), StandardCharsets.UTF_8));

        var json = HttpClientTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
        this.token = json.get("access_token").text();
        this.tokenExpire = Instant.now().plusSeconds(json.get("expires_in").asLong(0));
    }

    public String getToken() throws IOException {
        if (this.token == null || this.tokenExpire == null || this.tokenExpire.isBefore(Instant.now())) {
            this.requestToken();
        }
        return this.token;
    }

    public JsonBrowser getJson(String uri) throws IOException {
        var request = new HttpGet(uri);
        request.addHeader("Authorization", "Bearer " + this.getToken());
        return HttpClientTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
    }

    public AudioItem getSearch(String query) throws IOException {
        var json = this.getJson("https://api.spotify.com/v1/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=track,album,playlist,artist");
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist("Search results for: " + query, parseTracks(json), null, true);
    }

    public AudioItem getRecommendations(String query) throws IOException {
        var seedArtists = "";
        var seedTracks = "";
        var seedGenres = "";

        var i = 0;
        for (var seed : query.split("\\|")) {
            if (i == 0) {
                seedArtists = seed;
            } else if (i == 1) {
                seedTracks = seed;
            } else if (i == 2) {
                seedGenres = seed;
            } else {
                break;
            }
            i++;
        }

        var json = this.getJson("https://api.spotify.com/v1/recommendations"
                + "?seedArtists=" + URLEncoder.encode(seedArtists, StandardCharsets.UTF_8)
                + "&seedTracks=" + URLEncoder.encode(seedTracks, StandardCharsets.UTF_8)
                + "&seedGenres=" + URLEncoder.encode(seedGenres, StandardCharsets.UTF_8)
        );
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist("Search results for: " + query, parseTracks(json), null, true);
    }

    public AudioItem getAlbum(String id) throws IOException {
        var json = this.getJson("https://api.spotify.com/v1/albums/" + id);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        var tracks = new ArrayList<AudioTrack>();
        JsonBrowser page;
        var offset = 0;
        do {
            page = this.getJson("https://api.spotify.com/v1/albums/" + id + "/tracks?limit=" + ALBUM_MAX_PAGE_ITEMS + "&offset=" + offset);
            offset += ALBUM_MAX_PAGE_ITEMS;

            tracks.addAll(this.parseTrackItems(page));
        }
        while (page.get("next").text() != null);

        return new BasicAudioPlaylist(json.get("data").index(0).get("attributes").get("name").text(), tracks, null, false);

    }

    public AudioItem getPlaylist(String id) throws IOException {
        var json = this.getJson("https://api.spotify.com/v1/playlists/" + id);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        var tracks = new ArrayList<AudioTrack>();
        JsonBrowser page;
        var offset = 0;
        do {
            page = this.getJson("https://api.spotify.com/v1/playlists/" + id + "/tracks?limit=" + PLAYLIST_MAX_PAGE_ITEMS + "&offset=" + offset);
            offset += PLAYLIST_MAX_PAGE_ITEMS;

            tracks.addAll(this.parseTrackItems(page));
        }
        while (page.get("next").text() != null);

        return new BasicAudioPlaylist(json.get("data").index(0).get("attributes").get("name").text(), tracks, null, false);

    }

    public AudioItem getArtist(String id) throws IOException {
        var json = this.getJson("https://api.spotify.com/v1/artists/" + id + "/top-tracks");
        if (json == null) {
            return AudioReference.NO_TRACK;
        }
        return new BasicAudioPlaylist(json.get("tracks").index(0).get("artists").index(0).get("name").text() + "'s Top Tracks", this.parseTracks(json), null, false);
    }

    public AudioItem getTrack(String id) throws IOException {
        var json = this.getJson("https://api.spotify.com/v1/tracks/" + id);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }
        return parseTrack(json);
    }

    private List<AudioTrack> parseTracks(JsonBrowser json) {
        var tracks = new ArrayList<AudioTrack>();
        for (var value : json.get("tracks").values()) {
            tracks.add(this.parseTrack(value));
        }
        return tracks;
    }

    private List<AudioTrack> parseTrackItems(JsonBrowser json) {
        var tracks = new ArrayList<AudioTrack>();
        for (var value : json.get("items").values()) {
            tracks.add(this.parseTrack(value));
        }
        return tracks;
    }

    private AudioTrack parseTrack(JsonBrowser json) {
        return new SpotifyAudioTrack(
                new AudioTrackInfo(
                        json.get("name").text(),
                        json.get("artists").index(0).get("name").text(),
                        json.get("duration_ms").asLong(0),
                        json.get("id").text(),
                        false,
                        json.get("external_urls").get("spotify").text()
                ),
                json.get("external_ids").get("isrc").text(),
                json.get("album").get("images").index(0).get("url").text(),
                this
        );
    }

    @Override
    public void shutdown() {
        try {
            this.httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close HTTP interface manager", e);
        }
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        this.httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        this.httpInterfaceManager.configureBuilder(configurator);
    }

}
