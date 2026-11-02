package com.openstudy.modules.chat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.chat.repository.MessageRepository;
import com.openstudy.modules.chat.service.MessageService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The guard serializes sends, so a real race never reaches uk_messages_1. The lookup is blinded once here to force the
 * insert into the constraint, which is the path a repeat takes the moment sends stop sharing one guard.
 */
@MySqlIntegrationTest
@TestPropertySource(properties = "openstudy.workspace.scheduling.enabled=false")
class MessageDuplicateKeyTests extends ChannelTestSupport {
    @MockitoSpyBean MessageRepository messages;
    @Autowired MessageService service;

    /** The first lookup finds nothing; the next one, in the retry, finds the row as it really stands. */
    private void blindLookupOnce(String id) {
        Mockito.doReturn(Optional.empty()).doAnswer(call -> messages.findById(Long.valueOf(id))).when(messages)
                .findByConversationIdAndSenderEmployeeIdAndClientMessageKey(anyLong(), anyLong(), anyString());
    }

    @Test void aRepeatThatHitsTheConstraintGetsTheStoredRow() throws Exception {
        long channel = channelId(owner, a, "유일 제약", staffA);
        var first = service.send(staffA, channel, Map.of("clientMessageKey", "same-key", "text", "원본"));
        assertThat(first.created()).isTrue();

        blindLookupOnce(first.message().id());
        var repeat = service.send(staffA, channel, Map.of("clientMessageKey", "same-key", "text", "원본"));
        assertThat(repeat.created()).isFalse();
        assertThat(repeat.message().id()).isEqualTo(first.message().id());
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(1);
    }

    @Test void aDifferentInputThatHitsTheConstraintStillConflicts() throws Exception {
        long channel = channelId(owner, a, "유일 제약 충돌", staffA);
        var first = service.send(staffA, channel, Map.of("clientMessageKey", "same-key", "text", "원본"));

        blindLookupOnce(first.message().id());
        assertThatThrownBy(() -> service.send(staffA, channel, Map.of("clientMessageKey", "same-key", "text", "다른 본문")))
                .isInstanceOf(ApiException.class).hasMessageContaining("같은 키");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(1);
    }
}
