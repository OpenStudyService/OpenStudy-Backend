package com.openstudy.domainlens.api.ir;

/** Server-owned presentation contract. Model text is never an authority or the default UI message. */
public record OutcomeNotice(String code, Kind kind, String message, String nextAction) {
    public enum Kind { KNOWLEDGE, CLARIFICATION, UNSUPPORTED, VALIDATION, DENIED, FAILURE }
    public static OutcomeNotice from(IrDocument ir) {
        if(ir.status()==IrStatus.READY)return null;
        if(ir.reasonCode()!=null)return forCode(ir.reasonCode().name());
        return ir.status()==IrStatus.NEEDS_CLARIFICATION
            ? new OutcomeNotice("LEGACY_CLARIFICATION",Kind.CLARIFICATION,"조회 조건을 확정하지 못했습니다.","기간·대상·지표를 명시한 전체 질문으로 다시 요청해 주세요.")
            : new OutcomeNotice("LEGACY_UNSUPPORTED",Kind.UNSUPPORTED,"지원하지 않는 요청입니다.","등록된 업무 지표와 분석 범위로 요청해 주세요.");
    }
    public static OutcomeNotice forCode(String code) {
        return switch(code) {
            case "KNOWLEDGE_MISSING" -> new OutcomeNotice(code,Kind.KNOWLEDGE,"조회에 필요한 승인된 업무 정의가 제공되지 않았습니다.","업무 관리자에게 지표·공식 정의의 등록 및 제공 상태 확인을 요청해 주세요.");
            case "KNOWLEDGE_INSUFFICIENT" -> new OutcomeNotice(code,Kind.KNOWLEDGE,"질문의 의미를 확정할 업무 근거가 부족해 조회 요청을 거절했습니다.","등록된 지표명과 기준으로 질문을 다시 작성하거나 업무 관리자에게 정의 보완을 요청해 주세요.");
            case "REQUEST_INCOMPLETE" -> new OutcomeNotice(code,Kind.UNSUPPORTED,"필수 조회 조건을 확정할 수 없어 요청을 거절했습니다.","기간·대상·지표를 명시한 전체 질문으로 다시 요청해 주세요.");
            case "METRIC_AMBIGUOUS" -> new OutcomeNotice(code,Kind.CLARIFICATION,"조회할 지표의 의미를 확정하지 못했습니다.","조회하려는 지표의 기준을 명시한 전체 질문으로 다시 요청해 주세요.");
            case "REQUIRED_INPUT_MISSING" -> new OutcomeNotice(code,Kind.CLARIFICATION,"조회에 필요한 조건이 부족합니다.","기간·대상·지표를 명시한 전체 질문으로 다시 요청해 주세요.");
            case "SEMANTIC_GROUNDING_FAILED" -> new OutcomeNotice(code,Kind.VALIDATION,"질문과 승인된 업무 정의의 연결을 검증하지 못했습니다.","등록된 지표명과 조회 조건을 명시해 다시 요청해 주세요. 문제가 계속되면 관리자에게 문의해 주세요.");
            case "CAPABILITY_UNSUPPORTED", "UNSUPPORTED", "IR_UNSUPPORTED" -> new OutcomeNotice(code,Kind.UNSUPPORTED,"현재 지원하는 업무 분석 범위에 포함되지 않습니다.","등록된 업무 지표와 분석 범위로 요청해 주세요.");
            case "SCOPE_DENIED", "ACCESS_DENIED" -> new OutcomeNotice(code,Kind.DENIED,"현재 권한으로 이 작업을 할 수 없습니다.","권한이 허용된 대상만 조회해 주세요.");
            case "PII_DENIED" -> new OutcomeNotice(code,Kind.DENIED,"개인정보는 제공할 수 없습니다.","개인정보를 제외한 조회를 요청해 주세요.");
            case "FORMULA_CONFLICT" -> new OutcomeNotice(code,Kind.DENIED,"요청이 승인된 업무 공식의 조건과 충돌합니다.","공식의 제외 조건을 유지하는 조회를 요청해 주세요.");
            case "CLOCK_OUT_OF_RANGE" -> new OutcomeNotice(code,Kind.UNSUPPORTED,"요청한 기간이 조회 가능한 데이터 기간을 벗어났습니다.","조회 가능한 데이터 기간 안에서 다시 요청해 주세요.");
            case "MODEL_TIMEOUT" -> new OutcomeNotice(code,Kind.FAILURE,"모델 응답 시간이 초과되었습니다.","새 요청으로 다시 시도해 주세요.");
            case "QUERY_TIMEOUT" -> new OutcomeNotice(code,Kind.FAILURE,"데이터 조회 시간이 초과되었습니다.","조회 범위를 줄여 다시 시도해 주세요.");
            case "MODEL_TRUNCATED", "MODEL_INCOMPLETE" -> new OutcomeNotice(code,Kind.FAILURE,"모델 응답을 완성하지 못했습니다.","질문 범위를 줄여 다시 요청해 주세요.");
            case "IR_INVALID", "VALIDATION_ERROR" -> new OutcomeNotice(code,Kind.VALIDATION,"조회 계획이 검증을 통과하지 못했습니다.","조건을 명시해 다시 요청해 주세요. 문제가 계속되면 관리자에게 문의해 주세요.");
            case "NOT_READY" -> new OutcomeNotice(code,Kind.FAILURE,"데이터가 아직 준비되지 않았습니다.","관리자에게 데이터 준비 상태 확인을 요청해 주세요.");
            case "SERVER_INTERRUPTED" -> new OutcomeNotice(code,Kind.FAILURE,"서버가 재시작되어 요청이 중단되었습니다.","새 요청으로 다시 시도해 주세요.");
            default -> new OutcomeNotice(code,Kind.FAILURE,"요청을 처리하지 못했습니다.","새 요청으로 다시 시도해 주세요. 문제가 계속되면 관리자에게 문의해 주세요.");
        };
    }
}
