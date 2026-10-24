package com.os4.musiccover.lyrics;

import java.util.Objects;

/** An asynchronous lyric answer may only finish the same tap on the same bound capsule. */
public final class LyricFeedbackRequest {
    private final long request, binding;
    private final Object session, view;
    private final String song, lyric;

    public LyricFeedbackRequest(long request, Object session, String song, String lyric,
                                Object view, long binding) {
        this.request = request;
        this.session = session;
        this.song = song;
        this.lyric = lyric;
        this.view = view;
        this.binding = binding;
    }

    public boolean owns(long request, Object session, String song, String lyric,
                        Object view, long binding, boolean pageRequested) {
        return pageRequested && this.request == request && this.session != null
                && Objects.equals(this.session, session) && Objects.equals(this.song, song)
                && Objects.equals(this.lyric, lyric) && sameBinding(view, binding);
    }

    public boolean sameBinding(Object view, long binding) {
        return this.view == view && this.binding == binding;
    }
}
