package com.hyper.podsrx.hook;

/** Versioned protocol for the scoped Bluetooth host's temporary media envelope. */
public final class ConversationWire {
    private ConversationWire() {}
    public static final String APP = SpatialWire.APP;
    public static final String ACTION = APP + ".CONVERSATION_BRIDGE";
    public static final String PERMISSION = SpatialWire.PERMISSION;
    public static final String DESCRIPTOR = APP + ".Conversation.v1";
    public static final int QUERY = 1, START = 2, KEEPALIVE = 3, STOP = 4;
}
