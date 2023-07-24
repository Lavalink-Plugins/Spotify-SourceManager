package com.github.topi314.lavasrc.applemusic;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class AppleMusicSourceManager extends MirroringAudioSourceManager implements HttpConfigurable {

	public static final Pattern URL_PATTERN = Pattern.compile("(https?://)?(www\\.)?music\\.apple\\.com/(?<countrycode>[a-zA-Z]{2}/)?(?<type>album|playlist|artist|song)(/[a-zA-Z\\d\\-]+)?/(?<identifier>[a-zA-Z\\d\\-.]+)(\\?i=(?<identifier2>\\d+))?");
	public static final String SEARCH_PREFIX = "amsearch:";
	public static final int MAX_PAGE_ITEMS = 300;
	public static final String API_BASE = "https://api.music.apple.com/v1/";
	private static final Logger log = LoggerFactory.getLogger(AppleMusicSourceManager.class);
	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
	private final String countryCode;
	private int playlistPageLimit;
	private int albumPageLimit;
	private final String token;
	private String origin;
	private Instant tokenExpire;

	public AppleMusicSourceManager(String[] providers, String mediaAPIToken, String countryCode, AudioPlayerManager audioPlayerManager) {
		this(mediaAPIToken, countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public AppleMusicSourceManager(String mediaAPIToken, String countryCode, AudioPlayerManager audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		super(audioPlayerManager, mirroringAudioTrackResolver);
		if (mediaAPIToken == null || mediaAPIToken.isEmpty()) {
			throw new RuntimeException("Apple Music API token is empty or null");
		}
		this.token = mediaAPIToken;

		try {
			this.parseTokenData();
		} catch (IOException e) {
			throw new RuntimeException("Failed to parse Apple Music API token", e);
		}

		if (countryCode == null || countryCode.isEmpty()) {
			this.countryCode = "us";
		} else {
			this.countryCode = countryCode;
		}
	}

	public void setPlaylistPageLimit(int playlistPageLimit) {
		this.playlistPageLimit = playlistPageLimit;
	}

	public void setAlbumPageLimit(int albumPageLimit) {
		this.albumPageLimit = albumPageLimit;
	}

	@Override
	public String getSourceName() {
		return "applemusic";
	}

	@Override
	public boolean isTrackEncodable(AudioTrack track) {
		return true;
	}

	@Override
	public void encodeTrack(AudioTrack track, DataOutput output) {
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
		return new AppleMusicAudioTrack(trackInfo, this);
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		try {
			if (reference.identifier.startsWith(SEARCH_PREFIX)) {
				return this.getSearch(reference.identifier.substring(SEARCH_PREFIX.length()).trim());
			}

			var matcher = URL_PATTERN.matcher(reference.identifier);
			if (!matcher.find()) {
				return null;
			}

			var countryCode = matcher.group("countrycode");
			var id = matcher.group("identifier");
			switch (matcher.group("type")) {
				case "song":
					return this.getSong(id, countryCode);

				case "album":
					var id2 = matcher.group("identifier2");
					if (id2 == null || id2.isEmpty()) {
						return this.getAlbum(id, countryCode);
					}
					return this.getSong(id2, countryCode);

				case "playlist":
					return this.getPlaylist(id, countryCode);

				case "artist":
					return this.getArtist(id, countryCode);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	public void parseTokenData() throws IOException {
		var json = JsonBrowser.parse(new String(Base64.getDecoder().decode(this.token.split("\\.")[1])));
		this.tokenExpire = Instant.ofEpochSecond(json.get("exp").asLong(0));
		this.origin = json.get("root_https_origin").index(0).text();
	}

	public String getToken() throws IOException {
		if (this.tokenExpire.isBefore(Instant.now())) {
			throw new FriendlyException("Apple Music API token is expired", FriendlyException.Severity.SUSPICIOUS, null);
		}
		return this.token;
	}

	public JsonBrowser getJson(String uri) throws IOException {
		var request = new HttpGet(uri);
		request.addHeader("Authorization", "Bearer " + this.getToken());
		if (this.origin != null && !this.origin.isEmpty()) {
			request.addHeader("Origin", "https://" + this.origin);
		}
		return HttpClientTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	public AudioItem getSearch(String query) throws IOException {
		var json = this.getJson(API_BASE + "catalog/" + countryCode + "/search?term=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=" + 25);
		if (json == null || json.get("results").get("songs").get("data").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("Apple Music Search: " + query, this.parseTracks(json.get("results").get("songs")), null, true);
	}

	public AudioItem getAlbum(String id, String countryCode) throws IOException {
		var json = this.getJson(API_BASE + "catalog/" + countryCode + "/albums/" + id);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		JsonBrowser page;
		var offset = 0;
		var pages = 0;
		do {
			page = this.getJson(API_BASE + "catalog/" + countryCode + "/albums/" + id + "/tracks?limit=" + MAX_PAGE_ITEMS + "&offset=" + offset);
			offset += MAX_PAGE_ITEMS;

			tracks.addAll(this.parseTracks(page));
		}
		while (page.get("next").text() != null && ++pages < albumPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		var artworkUrl = this.parseArtworkUrl(json.get("data").index(0).get("attributes").get("artwork"));
		var author = json.get("data").index(0).get("attributes").get("artistName").text();
		return new AppleMusicAudioPlaylist(json.get("data").index(0).get("attributes").get("name").text(), tracks, "album", json.get("data").index(0).get("attributes").get("url").text(), artworkUrl, author);
	}

	public AudioItem getPlaylist(String id, String countryCode) throws IOException {
		var json = this.getJson(API_BASE + "catalog/" + countryCode + "/playlists/" + id);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		JsonBrowser page;
		var offset = 0;
		var pages = 0;
		do {
			page = this.getJson(API_BASE + "catalog/" + countryCode + "/playlists/" + id + "/tracks?limit=" + MAX_PAGE_ITEMS + "&offset=" + offset);
			offset += MAX_PAGE_ITEMS;

			tracks.addAll(parseTracks(page));
		}
		while (page.get("next").text() != null && ++pages < playlistPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		var artworkUrl = this.parseArtworkUrl(json.get("data").index(0).get("attributes").get("artwork"));
		var author = json.get("data").index(0).get("attributes").get("curatorName").text();
		return new AppleMusicAudioPlaylist(json.get("data").index(0).get("attributes").get("name").text(), tracks, "playlist", json.get("data").index(0).get("attributes").get("url").text(), artworkUrl, author);
	}

	public AudioItem getArtist(String id, String countryCode) throws IOException {
		var json = this.getJson(API_BASE + "catalog/" + countryCode + "/artists/" + id + "/view/top-songs");
		if (json == null || json.get("data").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		var jsonArtist = this.getJson(API_BASE + "catalog/" + countryCode + "/artists/" + id);

		var artworkUrl = this.parseArtworkUrl(jsonArtist.get("data").index(0).get("attributes").get("artwork"));
		var author = jsonArtist.get("data").index(0).get("attributes").get("name").text();
		return new AppleMusicAudioPlaylist(author + "'s Top Tracks", parseTracks(json), "artist", json.get("data").index(0).get("attributes").get("url").text(), artworkUrl, author);
	}

	public AudioItem getSong(String id, String countryCode) throws IOException {
		var json = this.getJson(API_BASE + "catalog/" + countryCode + "/songs/" + id);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}
		return parseTrack(json.get("data").index(0));
	}

	private List<AudioTrack> parseTracks(JsonBrowser json) {
		var tracks = new ArrayList<AudioTrack>();
		for (var value : json.get("data").values()) {
			tracks.add(this.parseTrack(value));
		}
		return tracks;
	}

	private AudioTrack parseTrack(JsonBrowser json) {
		var attributes = json.get("attributes");
		return new AppleMusicAudioTrack(
			new AudioTrackInfo(
				attributes.get("name").text(),
				attributes.get("artistName").text(),
				attributes.get("durationInMillis").asLong(0),
				json.get("id").text(),
				false,
				attributes.get("url").text(),
				this.parseArtworkUrl(attributes.get("artwork")),
				attributes.get("isrc").text()
			),
			this
		);
	}

	private String parseArtworkUrl(JsonBrowser json) {
		return json.get("url").text().replace("{w}", json.get("width").text()).replace("{h}", json.get("height").text());
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


	public static AppleMusicSourceManager fromMusicKitKey(String musicKitKey, String keyId, String teamId, String countryCode, AudioPlayerManager audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) throws NoSuchAlgorithmException, InvalidKeySpecException {
		var base64 = musicKitKey.replaceAll("-----BEGIN PRIVATE KEY-----\n", "")
				.replaceAll("-----END PRIVATE KEY-----", "")
				.replaceAll("\\s", "");
		var keyBytes = Base64.getDecoder().decode(base64);
		var spec = new PKCS8EncodedKeySpec(keyBytes);
		var keyFactory = KeyFactory.getInstance("EC");
		var key = (ECKey) keyFactory.generatePrivate(spec);
		var jwt = JWT.create()
				.withIssuer(teamId)
				.withIssuedAt(Instant.now())
				.withExpiresAt(Instant.now().plus(Duration.ofSeconds(15777000)))
				.withKeyId(keyId)
				.sign(Algorithm.ECDSA256(key));
		return new AppleMusicSourceManager(jwt, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

}
