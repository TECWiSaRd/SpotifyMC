package com.spotifymc.server;

/** A queued or playing track. Mutable fields are filled in once a client resolves the track. */
public final class Track {
    final long id;
    final String query;
    final String spotifyUri;
    final String requestedBy;
    String title;
    boolean titleKnown;
    long durationMs; // 0 = unknown
    boolean live;
    boolean fromRadio;
    String videoId;           // YouTube id if known, used to seed and de-duplicate radio
    boolean playingSomewhere; // a client reported audio, so don't time out

    Track(long id, TrackResolver.Request req, String requestedBy) {
        this.id = id;
        this.query = req.query();
        this.spotifyUri = req.spotifyUri();
        this.title = req.title();
        this.titleKnown = req.titleKnown();
        this.durationMs = req.durationMs();
        this.requestedBy = requestedBy;
    }
}
