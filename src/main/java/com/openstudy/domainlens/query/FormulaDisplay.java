package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.formula.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.work.ExecutionPlan;
import com.openstudy.domainlens.catalog.FormulaRegistry;
import java.util.*;
import java.math.*;
import static com.openstudy.domainlens.api.ir.ContractRules.require;

/** Presentation arithmetic also executes approved common operations on complete server outputs. */
public final class FormulaDisplay {
    private FormulaDisplay() {}
    public static List<FormulaDefinition.DisplayValues> render(FormulaRegistry registry,List<ExecutionPlan> plans,List<TypedDataset> datasets){
        var result=new ArrayList<FormulaDefinition.DisplayValues>();var seen=new HashSet<String>();
        for(var p:plans)for(var ref:p.formulas())if(seen.add(ref.id())){registry.requireRef(ref);var f=registry.get(ref.id());
            for(var v:f.presentations())if(!v.derived().isEmpty()){
                var ds=datasets.stream().filter(d->d.id().equals(v.output())).findFirst().orElse(null);if(ds==null)continue;
                require(ds.page()==0 && ds.totalElements()==ds.rows().size(),"Display calculation requires complete output");
                for(var slot:v.derived().entrySet()){var values=new ArrayList<BigDecimal>();for(var row:ds.rows())values.add(evaluate(slot.getValue(),row,ds.rows()));result.add(new FormulaDefinition.DisplayValues(ds.id(),slot.getKey(),values,ref));}
            }
        }return List.copyOf(result);
    }
    static BigDecimal evaluate(OperationIr e,Map<String,Object> row,List<Map<String,Object>> rows){
        if(e.op()==OperationIr.Op.COLUMN){Object value=row.get(e.ref());return value==null?null:new BigDecimal(value.toString());}
        if(e.op()==OperationIr.Op.LITERAL)return new BigDecimal(e.value().toString());
        if(e.op()==OperationIr.Op.SUM){BigDecimal total=BigDecimal.ZERO;for(var r:rows){var value=evaluate(e.args().getFirst(),r,List.of());if(value!=null)total=total.add(value);}return total;}
        var a=evaluate(e.args().get(0),row,rows);var b=evaluate(e.args().get(1),row,rows);if(e.op()==OperationIr.Op.COALESCE)return a==null?b:a;if(a==null || b==null)return null;
        return switch(e.op()){case ADD->a.add(b);case SUBTRACT->a.subtract(b);case MULTIPLY->a.multiply(b);case DIVIDE->b.signum()==0?null:a.divide(b,MathContext.DECIMAL128);default->throw new IllegalArgumentException("Unavailable display operation");};
    }
}
