package com.openstudy.domainlens.api.spi;

import java.util.*;

/** Consumer metadata for a new conversation, such as an explicit demo playback sequence. */
@FunctionalInterface
public interface ConversationExtension {
    Map<String,Object> created(UUID conversationId, boolean demoSession, UserContext user);
}
