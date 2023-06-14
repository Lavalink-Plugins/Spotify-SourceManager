package com.github.topisenpai.lavasrc.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.lavasrc.flowerytts")
@Component
public class FloweryTTSConfig {

    private String voice;
    private boolean translate;
    private int silence;
    private float speed;

    public String getVoice() {
        return this.voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public boolean getTranslate() {
        return this.translate;
    }

    public void setTranslate(boolean translate) {
        this.translate = translate;
    }

    public int getSilence(){
        return this.silence;
    }

    public void setSilence(int silence){
        this.silence = silence;
    }

    public float getSpeed(){
        return this.speed;
    }

    public void setSpeed(float speed){
        this.speed = speed;
    }

}