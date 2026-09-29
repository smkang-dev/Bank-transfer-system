package exception;

/** COMMIT 응답 유실 등을 실패 확정으로 표현하지 않는다. 같은 요청 ID로 재확인한다. */
public class TransferOutcomeUnknownException extends RuntimeException {
    public TransferOutcomeUnknownException(String requestId, Throwable cause) {
        super("처리 결과를 확정할 수 없습니다. 새 요청을 만들지 말고 같은 요청 ID로 재확인하세요: "
                + requestId, cause);
    }
}
