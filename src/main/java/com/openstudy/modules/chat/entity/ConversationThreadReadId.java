package com.openstudy.modules.chat.entity;

import java.io.Serializable;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class ConversationThreadReadId implements Serializable {
    private long conversationId;
    private long employeeId;
    private long parentMessageId;
}
