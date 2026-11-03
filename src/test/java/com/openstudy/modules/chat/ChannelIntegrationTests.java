package com.openstudy.modules.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #76: channels, members and the end of participation (collaboration-api.md, ADR-M7-001 M7-D04·D07). */
@MySqlIntegrationTest
@TestPropertySource(properties = "openstudy.workspace.scheduling.enabled=false")
class ChannelIntegrationTests extends ChannelTestSupport {

    // ---- who manages ----------------------------------------------------------------------------------------

    @Test void onlyAnOwnerCreatesAChannelForEveryone() throws Exception {
        var created = channel(owner, null, "전체 공지방", staffA, staffB);
        assertThat(created.get("kind").asString()).isEqualTo("전체");
        assertThat(created.get("branchId").isNull()).isTrue();
        assertThat(created.get("joined").asBoolean()).isTrue();
        assertThat(created.get("canManage").asBoolean()).isTrue();
        assertThat(created.get("memberCount").asLong()).isEqualTo(3);
        assertThat(created.get("version").asLong()).isEqualTo(1);
        assertThat(created.get("description").isNull()).isTrue();
        assertThat(created.get("unreadCount").asLong()).isZero();
        for (long person : List.of(managerA, staffA, dual))
            refused(createRaw(person, channelBody("전체", null, "거부", staffA), key()), 403, "FORBIDDEN");
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE name='거부'")).isZero();
    }

    @Test void aBranchChannelIsCreatedByAnOwnerOrThatBranchsManagerOnly() throws Exception {
        channel(owner, a, "사장 생성", staffA);
        var created = channel(managerA, a, "관리자 생성", staffA);
        assertThat(created.get("kind").asString()).isEqualTo("지점");
        assertThat(created.get("branchId").asString()).isEqualTo(Long.toString(a));
        for (long person : List.of(managerB, staffA, outsider))
            refused(createRaw(person, channelBody("지점", a, "거부", staffA), key()), 403, "FORBIDDEN");
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE name='거부'")).isZero();
    }

    @Test void theCreatorJoinsWithoutBeingListed() throws Exception {
        long channel = channelId(managerA, a, "자동 참여", staffA);
        assertThat(memberNames(managerA, channel)).containsExactly("가 관리자", "다 직원");
        assertThat(count("SELECT created_by FROM conversations WHERE id=?", channel)).isEqualTo(managerA);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='channels' AND entity_id=? AND action='CREATED'", channel)).isEqualTo(1);
    }

    @Test void creationRejectsMalformedInput() throws Exception {
        refused(createRaw(owner, channelBody("전체", a, "지점 보냄"), key()), 422, "VALIDATION_FAILED");
        refused(createRaw(owner, channelBody("지점", null, "지점 없음"), key()), 422, "VALIDATION_FAILED");
        refused(createRaw(owner, channelBody("DM", null, "종류 오류"), key()), 422, "VALIDATION_FAILED");
        refused(createRaw(owner, channelBody("전체", null, "   "), key()), 422, "VALIDATION_FAILED");
        refused(createRaw(owner, channelBody("전체", null, "가".repeat(121)), key()), 422, "VALIDATION_FAILED");
        refused(createRaw(owner, channelBody("전체", null, "중복", staffA, staffA), key()), 422, "VALIDATION_FAILED");
        var unknown = channelBody("전체", null, "모르는 필드");
        unknown.put("pinned", true);
        refused(createRaw(owner, unknown, key()), 422, "VALIDATION_FAILED");
        var noMembers = channelBody("전체", null, "목록 없음");
        noMembers.remove("memberIds");
        refused(createRaw(owner, noMembers, key()), 422, "VALIDATION_FAILED");
        var longDescription = channelBody("전체", null, "긴 설명");
        longDescription.put("description", "가".repeat(501));
        refused(createRaw(owner, longDescription, key()), 422, "VALIDATION_FAILED");
        refused(createRaw(owner, channelBody("전체", null, "키 없음"), null), 422, "VALIDATION_FAILED");
        refused(createRaw(owner, channelBody("전체", null, "짧은 키"), "short"), 422, "VALIDATION_FAILED");
    }

    @Test void repeatingACreationWithTheSameKeyReturnsTheStoredChannel() throws Exception {
        String key = key();
        var body = channelBody("지점", a, "재요청", staffA);
        var first = createRaw(managerA, body, key);
        var second = createRaw(managerA, body, key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(node(second)).isEqualTo(node(first));
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE name='재요청'")).isEqualTo(1);
        refused(createRaw(managerA, channelBody("지점", a, "다른 입력", staffA), key), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE name='다른 입력'")).isZero();
    }

    // ---- who may join ---------------------------------------------------------------------------------------

    @Test void aBranchChannelTakesOnlyActiveStaffOfThatBranchAndActiveOwners() throws Exception {
        // The OWNER has no membership row at the branch and still qualifies.
        assertThat(count("SELECT COUNT(*) FROM branch_memberships WHERE employee_id=?", owner)).isZero();
        long channel = channelId(managerA, a, "지점 자격", staffA, dual, owner);
        assertThat(memberNames(managerA, channel)).contains("사장", "바 겸직");
        for (long invalid : List.of(staffB, outsider, restricted, 999_999_999L)) {
            refused(createRaw(managerA, channelBody("지점", a, "거부", invalid), key()), 422, "INVALID_CHANNEL_MEMBER");
            refused(replaceMembers(managerA, channel, version(channel), staffA, invalid), 422, "INVALID_CHANNEL_MEMBER");
        }
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE name='거부'")).isZero();
    }

    @Test void aChannelForEveryoneTakesAnyActiveEmployeeWithAValidMembership() throws Exception {
        long channel = channelId(owner, null, "전체 자격", staffA, staffB, dual);
        assertThat(memberNames(owner, channel)).hasSize(4);
        for (long invalid : List.of(outsider, restricted))
            refused(replaceMembers(owner, channel, version(channel), invalid), 422, "INVALID_CHANNEL_MEMBER");
        long revokedOnly = person("EMPLOYEE", "소속 회수됨", "ACTIVE");
        member(revokedOnly, a, "STAFF");
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=?", revokedOnly);
        refused(replaceMembers(owner, channel, version(channel), revokedOnly), 422, "INVALID_CHANNEL_MEMBER");
    }

    @Test void candidatesFollowTheSameRule() throws Exception {
        var branch = names(ok(get(managerA, "/api/channels/member-candidates?branchId=" + a)));
        assertThat(branch).contains("사장", "가 관리자", "다 직원", "라 직원", "바 겸직").doesNotContain("나 관리자", "마 직원", "사 무소속", "아 제한");
        var everyone = names(ok(get(owner, "/api/channels/member-candidates")));
        assertThat(everyone).contains("사장", "가 관리자", "나 관리자", "마 직원").doesNotContain("사 무소속", "아 제한");
        refused(get(managerA, "/api/channels/member-candidates"), 403, "FORBIDDEN");
        refused(get(managerB, "/api/channels/member-candidates?branchId=" + a), 403, "FORBIDDEN");
        refused(get(staffA, "/api/channels/member-candidates?branchId=" + a), 403, "FORBIDDEN");
        refused(get(owner, "/api/channels/member-candidates?branchId=999999999"), 404, "NOT_FOUND");
    }

    private List<String> names(tools.jackson.databind.JsonNode list) {
        var result = new ArrayList<String>();
        for (var item : list.get("items")) result.add(item.get("name").asString());
        return result;
    }

    // ---- reading channels -----------------------------------------------------------------------------------

    @Test void theListShowsMyChannelsByNameWithinTheScope() throws Exception {
        channelId(owner, null, "나 전체", dual);
        channelId(owner, a, "가 지점", dual);
        channelId(owner, b, "다 지점", dual);
        channelId(owner, a, "라 다른 사람", staffA);
        assertThat(channelNames(dual, "")).containsExactly("가 지점", "나 전체", "다 지점");
        assertThat(channelNames(dual, "?scope=" + a)).containsExactly("가 지점", "나 전체");
        assertThat(channelNames(dual, "?scope=" + b)).containsExactly("나 전체", "다 지점");
        refused(get(staffA, "/api/channels?scope=" + b), 403, "FORBIDDEN");
        refused(get(staffA, "/api/channels?scope=abc"), 422, "VALIDATION_FAILED");
        // An owner who has not joined sees no channel here: membership decides, not the role.
        assertThat(channelNames(owner, "")).contains("가 지점", "나 전체", "다 지점", "라 다른 사람");
        assertThat(channelNames(managerA, "")).isEmpty();
    }

    @Test void aManagerSeesSettingsButNoMessageFactsOfAChannelNotJoined() throws Exception {
        long channel = channelId(owner, a, "관리만", staffA);
        sent(staffA, channel, "관리자가 보면 안 되는 활동");
        var seen = detail(managerA, channel);
        assertThat(seen.get("joined").asBoolean()).isFalse();
        assertThat(seen.get("canManage").asBoolean()).isTrue();
        assertThat(seen.get("unreadCount").asLong()).isZero();
        assertThat(seen.get("lastMessageAt").isNull()).isTrue();
        assertThat(seen.get("lastReadMessageId").isNull()).isTrue();
        assertThat(seen.get("memberCount").asLong()).isEqualTo(2);
        assertThat(memberNames(managerA, channel)).containsExactly("다 직원", "사장");
        assertThat(detail(staffA, channel).get("lastMessageAt").isNull()).isFalse();
        assertThat(detail(staffA, channel).get("canManage").asBoolean()).isFalse();
    }

    @Test void whoeverNeitherJoinedNorManagesGets404ForTheChannelToo() throws Exception {
        long channel = channelId(owner, a, "비공개", staffA);
        for (long person : List.of(staffA2, managerB, dual, staffB)) {
            refused(get(person, "/api/channels/" + channel), 404, "NOT_FOUND");
            refused(get(person, "/api/channels/" + channel + "/members"), 404, "NOT_FOUND");
            refused(write(person, "PATCH", "/api/channels/" + channel, Map.of("version", 1, "name", "변경")), 404, "NOT_FOUND");
            refused(replaceMembers(person, channel, 1, staffA), 404, "NOT_FOUND");
        }
        refused(get(owner, "/api/channels/999999999"), 404, "NOT_FOUND");
        refused(get(owner, "/api/channels/abc"), 422, "VALIDATION_FAILED");
    }

    @Test void manageableChannelsArePagedAndCoverOnlyWhatTheRequesterManages() throws Exception {
        channelId(owner, null, "ㄱ 전체", staffA);
        channelId(owner, a, "ㄴ A", staffA);
        channelId(owner, a, "ㄷ A", staffA);
        channelId(owner, b, "ㄹ B", staffB);
        var mine = ok(get(managerA, "/api/channels/manageable"));
        assertThat(mine.get("meta").get("total").asLong()).isEqualTo(2);
        var listed = new ArrayList<String>();
        for (var item : mine.get("items")) { listed.add(item.get("name").asString()); assertThat(item.get("joined").asBoolean()).isFalse(); assertThat(item.get("canManage").asBoolean()).isTrue(); }
        assertThat(listed).containsExactly("ㄴ A", "ㄷ A");
        var owners = ok(get(owner, "/api/channels/manageable?pageSize=2&page=2"));
        assertThat(owners.get("meta").get("total").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(owners.get("items").size()).isEqualTo(2);
        var scoped = ok(get(owner, "/api/channels/manageable?scope=" + b));
        var names = new ArrayList<String>();
        for (var item : scoped.get("items")) names.add(item.get("name").asString());
        assertThat(names).contains("ㄱ 전체", "ㄹ B").doesNotContain("ㄴ A");
        refused(get(staffA, "/api/channels/manageable"), 403, "FORBIDDEN");
        refused(get(managerA, "/api/channels/manageable?scope=" + b), 403, "FORBIDDEN");
        refused(get(owner, "/api/channels/manageable?pageSize=101"), 422, "VALIDATION_FAILED");
    }

    @Test void aBranchNameOrIdNeverLeaksThroughAChannelForEveryone() throws Exception {
        long channel = channelId(owner, null, "전체 노출", staffA, staffB);
        var seen = get(staffA, "/api/channels/" + channel);
        assertThat(seen.body()).doesNotContain("채널 A 지점").doesNotContain("채널 B 지점").contains("\"branchId\":null");
        var members = get(staffA, "/api/channels/" + channel + "/members");
        assertThat(members.body()).contains("마 직원").doesNotContain("branch").doesNotContain("email");
    }

    // ---- changing a channel ---------------------------------------------------------------------------------

    @Test void managersEditTheNameAndDescriptionAndAnyoneElseIsRefused() throws Exception {
        long channel = channelId(owner, a, "수정 전", staffA);
        var edited = ok(write(managerA, "PATCH", "/api/channels/" + channel, Map.of("version", 1, "name", "  수정 후  ", "description", "설명")));
        assertThat(edited.get("name").asString()).isEqualTo("수정 후");
        assertThat(edited.get("description").asString()).isEqualTo("설명");
        assertThat(edited.get("version").asLong()).isEqualTo(2);
        refused(write(staffA, "PATCH", "/api/channels/" + channel, Map.of("version", 2, "name", "직원")), 403, "FORBIDDEN");

        var cleared = new HashMap<String, Object>();
        cleared.put("version", 2); cleared.put("description", null);
        assertThat(ok(write(owner, "PATCH", "/api/channels/" + channel, cleared)).get("description").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='channels' AND entity_id=? AND action='UPDATED'", channel)).isEqualTo(2);

        long global = channelId(owner, null, "전체 수정", managerA, staffA);
        refused(write(managerA, "PATCH", "/api/channels/" + global, Map.of("version", 1, "name", "관리자")), 403, "FORBIDDEN");
    }

    @Test void aStaleVersionConflictsAndAnIdenticalEditChangesNothing() throws Exception {
        long channel = channelId(owner, a, "버전", staffA);
        ok(write(owner, "PATCH", "/api/channels/" + channel, Map.of("version", 1, "name", "버전 2")));
        var stale = write(owner, "PATCH", "/api/channels/" + channel, Map.of("version", 1, "name", "늦은 수정"));
        refused(stale, 409, "VERSION_CONFLICT");
        assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
        assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/channels/" + channel);
        assertThat(ok(write(owner, "PATCH", "/api/channels/" + channel, Map.of("version", 2, "name", "버전 2"))).get("version").asLong()).isEqualTo(2);
        refused(write(owner, "PATCH", "/api/channels/" + channel, Map.of("version", 2)), 422, "VALIDATION_FAILED");
        refused(write(owner, "PATCH", "/api/channels/" + channel, Map.of("name", "버전 없음")), 422, "VALIDATION_FAILED");
        refused(write(owner, "PATCH", "/api/channels/" + channel, Map.of("version", 2, "kind", "전체")), 422, "IMMUTABLE_FIELD");
        refused(write(owner, "PATCH", "/api/channels/" + channel, Map.of("version", 2, "branchId", Long.toString(b))), 422, "IMMUTABLE_FIELD");
        refused(write(owner, "PATCH", "/api/channels/" + channel, Map.of("version", 2, "memberIds", List.of())), 422, "VALIDATION_FAILED");
    }

    @Test void theMemberListIsReplacedAsAWholeAndARemovedMemberLosesAccessAtOnce() throws Exception {
        long channel = channelId(managerA, a, "교체", staffA);
        long message = sent(staffA, channel, "원문");
        assertThat(ok(get(staffA, "/api/channels/" + channel + "/messages")).get("items").size()).isEqualTo(1);

        var replaced = ok(replaceMembers(managerA, channel, 1, managerA, staffA2));
        assertThat(replaced.get("items").size()).isEqualTo(2);
        assertThat(version(channel)).isEqualTo(2);
        assertThat(leftAtIsSet(channel, staffA)).isEqualTo(1);
        assertThat(detail(managerA, channel).get("memberCount").asLong()).isEqualTo(2);
        refused(get(staffA, "/api/channels/" + channel), 404, "NOT_FOUND");
        refused(get(staffA, "/api/channels/" + channel + "/messages/" + message), 404, "NOT_FOUND");
        refused(send(staffA, channel, key(), "차단", null), 404, "NOT_FOUND");
        assertThat(channelNames(staffA, "")).doesNotContain("교체");
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='channels' AND entity_id=? AND action='MEMBERS_CHANGED'", channel)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(1);
    }

    @Test void joiningAgainClearsLeftAtAndRestoresEarlierMessagesAndMovesTheReadPosition() throws Exception {
        long channel = channelId(managerA, a, "재참여", staffA);
        long first = sent(managerA, channel, "이전 메시지");
        ok(read(staffA, channel, first));
        ok(replaceMembers(managerA, channel, 1, managerA));
        long second = sent(managerA, channel, "떠난 동안의 메시지");

        ok(replaceMembers(managerA, channel, 2, managerA, staffA));
        assertThat(leftAtIsSet(channel, staffA)).isZero();
        assertThat(items(ok(get(staffA, "/api/channels/" + channel + "/messages")))).containsExactly(first, second);
        // M7-D09: the messages stay open and count as read. ThreadReadIntegrationTests covers the threads.
        var seen = detail(staffA, channel);
        assertThat(seen.get("lastReadMessageId").asString()).isEqualTo(Long.toString(second));
        assertThat(seen.get("unreadCount").asLong()).isZero();
    }

    @Test void anIdenticalListChangesNothingAndAStaleOneConflicts() throws Exception {
        long channel = channelId(managerA, a, "동일 목록", staffA);
        ok(replaceMembers(managerA, channel, 1, staffA, managerA));
        assertThat(version(channel)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='channels' AND entity_id=? AND action='MEMBERS_CHANGED'", channel)).isZero();

        ok(replaceMembers(managerA, channel, 1, staffA, managerA, staffA2));
        var stale = replaceMembers(managerA, channel, 1, staffA);
        refused(stale, 409, "VERSION_CONFLICT");
        assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
        assertThat(memberNames(managerA, channel)).hasSize(3);
        // The stale version wins over the eligibility of the list, which may have been valid when it was read.
        refused(replaceMembers(managerA, channel, 1, staffB), 409, "VERSION_CONFLICT");
    }

    @Test void onlyManagersChangeMembersAndAnEmptyListIsAllowed() throws Exception {
        long channel = channelId(owner, a, "참여자 권한", staffA, managerA);
        refused(replaceMembers(staffA, channel, 1, staffA), 403, "FORBIDDEN");
        ok(replaceMembers(managerA, channel, 1));
        assertThat(detail(managerA, channel).get("memberCount").asLong()).isZero();
        long global = channelId(owner, null, "전체 참여자", managerA);
        refused(replaceMembers(managerA, global, 1, managerA), 403, "FORBIDDEN");
        refused(write(owner, "PUT", "/api/channels/" + global + "/members", Map.of("version", 1, "employeeIds", ids(staffA, staffA))), 422, "VALIDATION_FAILED");
        refused(write(owner, "PUT", "/api/channels/" + global + "/members", Map.of("version", 1)), 422, "VALIDATION_FAILED");
        var tooMany = new ArrayList<String>();
        for (int index = 1; index <= 501; index++) tooMany.add(Integer.toString(index));
        refused(write(owner, "PUT", "/api/channels/" + global + "/members", Map.of("version", 1, "employeeIds", tooMany)), 422, "VALIDATION_FAILED");
    }

    @Test void anAnonymousRequestIsRejected() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        assertThat(http(client, "GET", "/api/channels", null, null, null).statusCode()).isEqualTo(401);
        client.close();
    }

    // ---- end of participation (M7-D07) ----------------------------------------------------------------------

    @Test void revokingAMembershipEndsThatBranchsChannelsAtOnce() throws Exception {
        long channelA = channelId(owner, a, "A 채널", dual, staffA);
        long channelB = channelId(owner, b, "B 채널", dual);
        long global = channelId(owner, null, "전체 채널", dual);
        long message = sent(owner, channelA, "소속 회수 전");
        assertThat(ok(revoke(dual, a)).get("membership").get("status").asString()).isEqualTo("회수");

        assertThat(leftAtIsSet(channelA, dual)).isEqualTo(1);
        assertThat(version(channelA)).isEqualTo(2);
        refused(get(dual, "/api/channels/" + channelA), 404, "NOT_FOUND");
        refused(get(dual, "/api/channels/" + channelA + "/messages"), 404, "NOT_FOUND");
        refused(get(dual, "/api/channels/" + channelA + "/messages/" + message), 404, "NOT_FOUND");
        refused(send(dual, channelA, key(), "차단", null), 404, "NOT_FOUND");
        refused(read(dual, channelA, message), 404, "NOT_FOUND");
        // What is left of the employee's access is untouched.
        assertThat(leftAtIsSet(channelB, dual)).isZero();
        assertThat(leftAtIsSet(global, dual)).isZero();
        assertThat(version(channelB)).isEqualTo(1);
        assertThat(channelNames(dual, "")).containsExactly("B 채널", "전체 채널");
        sent(dual, global, "다른 소속이 남아 있다");
    }

    @Test void losingTheLastMembershipEndsTheChannelsForEveryoneToo() throws Exception {
        long global = channelId(owner, null, "전체 끝", staffA, dual);
        ok(revoke(staffA, a));
        assertThat(leftAtIsSet(global, staffA)).isEqualTo(1);
        refused(get(staffA, "/api/channels/" + global + "/messages"), 404, "NOT_FOUND");
        assertThat(channelNames(staffA, "")).isEmpty();
        ok(revoke(dual, a));
        assertThat(leftAtIsSet(global, dual)).isZero();
        ok(revoke(dual, b));
        assertThat(leftAtIsSet(global, dual)).isEqualTo(1);
        assertThat(version(global)).isEqualTo(3);
    }

    @Test void disablingAnAccountEndsEveryChannelAndRestoringItDoesNotRejoin() throws Exception {
        for (String status : List.of("이용 제한", "비활성")) {
            long target = person("EMPLOYEE", "대상 " + status, "ACTIVE");
            member(target, a, "STAFF");
            long branch = channelId(owner, a, "지점 " + status, target);
            long global = channelId(owner, null, "전체 " + status, target);
            sent(target, branch, "접근 가능");
            long before = version(branch);

            ok(accountStatus(owner, target, status));
            assertThat(leftAtIsSet(branch, target)).isEqualTo(1);
            assertThat(leftAtIsSet(global, target)).isEqualTo(1);
            assertThat(version(branch)).isEqualTo(before + 1);
            // The account's sessions ended with it.
            assertThat(get(target, "/api/channels/" + branch).statusCode()).isEqualTo(401);
            sessions.remove(target);

            ok(accountStatus(owner, target, "활성"));
            assertThat(leftAtIsSet(branch, target)).isEqualTo(1);
            assertThat(channelNames(target, "")).isEmpty();
            refused(get(target, "/api/channels/" + branch), 404, "NOT_FOUND");
            refused(send(target, branch, key(), "복구 후 전송", null), 404, "NOT_FOUND");

            ok(replaceMembers(owner, branch, version(branch), owner, target));
            assertThat(leftAtIsSet(branch, target)).isZero();
            assertThat(ok(get(target, "/api/channels/" + branch + "/messages")).get("items").size()).isEqualTo(1);
        }
    }

    /** The schema allows one OWNER, who is the last administrator: the change fails and takes nothing with it. */
    @Test void aFailedAccountChangeLeavesParticipationAlone() throws Exception {
        long channel = channelId(owner, null, "롤백", staffA);
        refused(accountStatus(owner, owner, "비활성"), 409, "LAST_ADMIN_REQUIRED");
        assertThat(leftAtIsSet(channel, owner)).isZero();
        assertThat(version(channel)).isEqualTo(1);
    }

    @Test void everyRequestChecksTheCurrentEligibilityEvenWhenARowIsStillOpen() throws Exception {
        long channelA = channelId(owner, a, "남은 행", staffA);
        long global = channelId(owner, null, "남은 전체", staffA);
        long message = sent(staffA, channelA, "접근 가능");
        // A write that skipped the service: the row stays open, and it must not open anything.
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=?", staffA);
        assertThat(leftAtIsSet(channelA, staffA)).isZero();

        refused(get(staffA, "/api/channels/" + channelA), 404, "NOT_FOUND");
        refused(get(staffA, "/api/channels/" + global), 404, "NOT_FOUND");
        refused(get(staffA, "/api/channels/" + channelA + "/members"), 404, "NOT_FOUND");
        refused(get(staffA, "/api/channels/" + channelA + "/messages"), 404, "NOT_FOUND");
        refused(get(staffA, "/api/channels/" + channelA + "/messages/" + message + "/replies"), 404, "NOT_FOUND");
        refused(send(staffA, channelA, key(), "남은 행으로 전송", null), 404, "NOT_FOUND");
        refused(read(staffA, channelA, message), 404, "NOT_FOUND");
        assertThat(channelNames(staffA, "")).isEmpty();
    }

    @Test void aSuspendedAccountWhoseRowIsStillOpenIsRefusedByItsStatus() throws Exception {
        long channel = channelId(owner, a, "상태 재확인", staffA);
        as(staffA);
        jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", staffA);
        assertThat(get(staffA, "/api/channels/" + channel).statusCode()).isIn(401, 403);
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isZero();
    }

    @Test void aDemotedManagerStaysAParticipantButStopsManaging() throws Exception {
        long channel = channelId(managerA, a, "강등", staffA);
        var current = ok(get(owner, "/api/employees/" + managerA));
        var membership = current.get("memberships").get(0);
        var body = new HashMap<String, Object>();
        body.put("employeeVersion", current.get("version").asLong()); body.put("version", membership.get("version").asLong());
        body.put("role", "staff"); body.put("status", "활성"); body.put("permissions", List.of());
        ok(write(owner, "PUT", "/api/employees/" + managerA + "/memberships/" + a, body));
        assertThat(leftAtIsSet(channel, managerA)).isZero();
        assertThat(detail(managerA, channel).get("canManage").asBoolean()).isFalse();
        refused(replaceMembers(managerA, channel, version(channel), staffA), 403, "FORBIDDEN");
        sent(managerA, channel, "참여는 유지");
    }

    // ---- races ----------------------------------------------------------------------------------------------

    @Test void twoMemberChangesOnTheSameVersionLeaveExactlyOneWinner() throws Exception {
        long channel = channelId(owner, a, "참여자 경합", staffA);
        var left = login(owner);
        var right = login(managerA);
        try {
            var results = race(List.<java.util.concurrent.Callable<java.net.http.HttpResponse<String>>>of(
                    () -> http(left.client(), "PUT", "/api/channels/" + channel + "/members", left.token(), Map.of("version", 1, "employeeIds", ids(owner, staffA2)), null),
                    () -> http(right.client(), "PUT", "/api/channels/" + channel + "/members", right.token(), Map.of("version", 1, "employeeIds", ids(owner, dual)), null)));
            assertThat(statuses(results)).containsExactly(200, 409);
            for (var result : results) if (result.statusCode() == 409) assertThat(code(result)).isEqualTo("VERSION_CONFLICT");
            assertThat(version(channel)).isEqualTo(2);
            var names = memberNames(owner, channel);
            assertThat(names).hasSize(2).contains("사장");
            assertThat(names.contains("라 직원") ^ names.contains("바 겸직")).isTrue();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='channels' AND entity_id=? AND action='MEMBERS_CHANGED'", channel)).isEqualTo(1);
        } finally {
            left.close(); right.close();
        }
    }

    @Test void concurrentCreationsWithTheSameKeyMakeOneChannel() throws Exception {
        String key = key();
        var body = channelBody("지점", a, "동시 생성", staffA);
        var open = new ArrayList<Session>();
        try {
            var calls = new ArrayList<java.util.concurrent.Callable<java.net.http.HttpResponse<String>>>();
            for (int index = 0; index < 4; index++) {
                var session = login(managerA);
                open.add(session);
                calls.add(() -> http(session.client(), "POST", "/api/channels", session.token(), body, key));
            }
            var results = race(calls);
            assertThat(statuses(results)).containsOnly(201);
            assertThat(results.stream().map(result -> node(result).get("id").asString()).distinct().count()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM conversations WHERE name='동시 생성'")).isEqualTo(1);
        } finally {
            open.forEach(Session::close);
        }
    }
}
