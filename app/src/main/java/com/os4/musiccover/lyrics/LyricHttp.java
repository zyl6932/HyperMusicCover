package com.os4.musiccover.lyrics;

import com.os4.musiccover.Http;

/** Check again at each request, including follow-up lookups and catalogue health probes. */
final class LyricHttp {
    private LyricHttp() { }
    static Http.Reply get(String url, String tag) {
        return LyricSourcePolicy.current.allowNetwork ? Http.get(url, tag) : new Http.Reply(null, 0);
    }
    static Http.Raw request(String url, String tag, String body, String... headers) {
        return LyricSourcePolicy.current.allowNetwork
                ? Http.request(url, tag, body, headers) : new Http.Raw(null, 0);
    }
}
