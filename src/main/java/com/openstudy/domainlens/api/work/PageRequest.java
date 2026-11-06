package com.openstudy.domainlens.api.work;
import com.openstudy.domainlens.api.ir.QuerySpec;
import java.util.List;
import org.jspecify.annotations.Nullable;
public record PageRequest(@Nullable Integer page,@Nullable Integer size,@Nullable List<QuerySpec.Order> orderBy) {
    public PageRequest {page=page==null?0:page;size=size==null?10:size;if(orderBy!=null)orderBy=List.copyOf(orderBy);}
}
