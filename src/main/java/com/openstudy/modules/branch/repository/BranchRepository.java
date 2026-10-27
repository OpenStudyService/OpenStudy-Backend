package com.openstudy.modules.branch.repository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.branch.dto.Branch;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;

@Repository
public class BranchRepository {
    private static final String CREATE="branches:create";
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;
    public BranchRepository(JdbcTemplate jdbc,AuditTrail audit,WorkspaceOutbox outbox,IdempotencyStore requests,Clock clock) {
        this.jdbc=jdbc; this.audit=audit; this.outbox=outbox; this.requests=requests; this.clock=clock;
    }

    public List<Long> ids() { return jdbc.queryForList("SELECT id FROM branches ORDER BY id", Long.class); }
    public Branch get(long id) {
        var rows=jdbc.query("SELECT * FROM branches WHERE id=?", (rs,n)->new Branch(rs.getString("id"),
                ApiVersion.fromDatabase(rs.getLong("version")),rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString(),rs.getString("id"),rs.getString("code"),
                rs.getString("name"),rs.getString("address"),rs.getString("phone")==null?"":rs.getString("phone"),
                "", hours(id),"ACTIVE".equals(rs.getString("status"))?"운영 중":"운영 중지"),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var b=rows.getFirst();
        String display=b.weeklyHours().stream().map(h->h.weekday()+"요일 "+(h.isClosed()?"휴무":time(h.opensMinute())+"~"+time(h.closesMinute())))
                .collect(java.util.stream.Collectors.joining(", "));
        return new Branch(b.id(),b.version(),b.createdAt(),b.updatedAt(),b.branchId(),b.code(),b.name(),b.address(),b.phone(),display,b.weeklyHours(),b.status());
    }
    private static String time(int minute) { return (minute>=1440?"익일 ":"")+String.format("%02d:%02d",(minute%1440)/60,minute%60); }
    private List<WeeklyHours> hours(long id) {
        var stored=jdbc.query("SELECT * FROM branch_weekly_hours WHERE branch_id=? ORDER BY weekday", (rs,n)->
                new WeeklyHours(rs.getInt("weekday"),rs.getBoolean("is_closed"),(Integer)rs.getObject("opens_minute"),
                        (Integer)rs.getObject("closes_minute")),id);
        // Legacy branches without a configured schedule are explicitly closed, never inferred open.
        return java.util.stream.IntStream.rangeClosed(1,7).mapToObj(day->stored.stream().filter(h->h.weekday()==day)
                .findFirst().orElse(new WeeklyHours(day,true,null,null))).toList();
    }
    public long create(String name,String address,String phone,String status,List<WeeklyHours> hours) {
        String code="BR-"+UUID.randomUUID().toString().replace("-", "").substring(0,29);
        jdbc.update("INSERT INTO branches(code,name,address,phone,status) VALUES(?,?,?,?,?)",code,name,address,phone,status);
        long id=jdbc.queryForObject("SELECT id FROM branches WHERE code=?",Long.class,code);
        replaceHours(id,hours); return id;
    }
    public void update(long id,String name,String address,String phone,List<WeeklyHours> hours) {
        jdbc.update("UPDATE branches SET name=?,address=?,phone=?,version=version+1 WHERE id=?",name,address,phone,id);
        replaceHours(id,hours);
    }
    public void status(long id,String status) { jdbc.update("UPDATE branches SET status=?,version=version+1 WHERE id=?",status,id); }
    private void replaceHours(long id,List<WeeklyHours> hours) {
        jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?",id);
        for(var h:hours) jdbc.update("INSERT INTO branch_weekly_hours(branch_id,weekday,is_closed,opens_minute,closes_minute) VALUES(?,?,?,?,?)",
                id,h.weekday(),h.isClosed(),h.opensMinute(),h.closesMinute());
    }
    public List<StoredRequest> request(long actor,String key) {
        return requests.lock(actor,CREATE,"all",key).map(r->new StoredRequest(r.getRequestHash(),r.getResponseJson())).stream().toList();
    }
    public record StoredRequest(byte[] hash,String response) {}
    public void saveRequest(long actor,String key,byte[] hash,String response) {
        requests.complete(actor,CREATE,"all",key,hash,201,response,clock.instant().plus(Duration.ofHours(24)));
    }
    public void audit(long actor,long id,String action,String before,String after,String requestId) {
        audit.record(AuditEvent.change(actor,id,"branches",id,action,before,after,requestId));
        outbox.invalidate(id,"branches",id);
    }
}
