package service;

import dao.AccountDAO;
import dao.TransactionDAO;
import domain.Account;
import domain.Transaction;
import exception.*;
import util.ConnectionFactory;
import util.DBConnection;
import util.PasswordHasher;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class TransferService {
    private final AccountDAO accounts;
    private final TransactionDAO transactions;
    private final ConnectionFactory connections;

    public TransferService() {
        this(new AccountDAO(), new TransactionDAO(), DBConnection::getConnection);
    }

    // 실제 MySQL 테스트에서 독립 연결과 실패 주입을 제공할 수 있다.
    public TransferService(AccountDAO accounts, TransactionDAO transactions,
                           ConnectionFactory connections) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.connections = connections;
    }

    // 기존 호출과의 호환. 재시도 안전성이 필요한 호출은 반드시 requestId 오버로드 사용.
    public void transfer(int userId, String from, String to, long amount, String password) {
        transfer(userId, from, to, amount, password, UUID.randomUUID().toString());
    }

    public int transfer(int userId, String from, String to, long amount,
                        String password, String requestId) {
        if (userId <= 0) throw new IllegalArgumentException("잘못된 사용자입니다.");
        if (from == null || to == null || from.isBlank() || to.isBlank())
            throw new IllegalArgumentException("출금·입금 계좌번호를 입력하세요.");
        from = from.trim();
        to = to.trim();
        if (from.equals(to)) throw new IllegalArgumentException("동일 계좌 이체 불가");
        if (amount <= 0) throw new IllegalArgumentException("금액은 양수여야 합니다.");
        if (password == null || password.isBlank())
            throw new IllegalArgumentException("계좌 비밀번호를 입력하세요.");
        if (requestId == null || !requestId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("올바른 UUID 요청 ID가 필요합니다.");
        requestId = UUID.fromString(requestId).toString();

        Connection conn = null;
        boolean commitStarted = false;
        try {
            conn = connections.open();
            // 잠금 대기 전에 읽은 스냅샷 때문에 오늘 합계가 오래된 값이 되는 것을 방지.
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);

            Account initialFrom = accounts.findByAccountNumber(conn, from);
            Account initialTo = accounts.findByAccountNumber(conn, to);
            if (initialFrom == null || initialTo == null)
                throw new AccountNotFoundException("출금 또는 입금 계좌가 없습니다.");

            // 비싼 해시 검증은 잠금 전에 수행하고 잠금 후 동일 해시인지 재확인한다.
            authenticate(initialFrom, userId, password);
            List<Account> locked = accounts.lockAccountsInOrder(conn,
                    initialFrom.getAccountId(), initialTo.getAccountId());
            Account source = find(locked, initialFrom.getAccountId());
            Account target = find(locked, initialTo.getAccountId());
            if (!source.getAccountNumber().equals(from) || !target.getAccountNumber().equals(to))
                throw new TransferFailedException("계좌 정보가 변경됐습니다. 다시 확인하세요.");
            if (source.getUserId() != userId)
                throw new UnauthorizedAccountAccessException("본인 계좌가 아닙니다.");
            if (!source.getAccountPassword().equals(initialFrom.getAccountPassword())
                    && !PasswordHasher.matches(password, source.getAccountPassword()))
                throw new InvalidAccountPasswordException("계좌 비밀번호가 변경됐습니다.");

            TransactionDAO.RequestRecord previous = transactions.findByRequestId(conn, requestId);
            if (previous != null) {
                if (previous.userId() != userId || previous.fromId() != source.getAccountId()
                        || previous.toId() != target.getAccountId() || previous.amount() != amount)
                    throw new IllegalArgumentException("동일 요청 ID에 다른 이체 내용을 사용할 수 없습니다.");
                // 이미 성공한 요청은 현재 잔액·한도와 무관하게 기존 결과 반환.
                conn.rollback(); // 이 경로에서는 데이터 변경 없음. 잠금만 해제.
                return previous.transactionId();
            }

            if (!"ACTIVE".equals(source.getStatus()) || !"ACTIVE".equals(target.getStatus()))
                throw new InvalidAccountStatusException("정상 상태의 계좌가 아닙니다.");
            if (source.getBalance() < 0 || target.getBalance() < 0
                    || source.getOneTimeLimit() < 0 || source.getDailyLimit() < 0)
                throw new TransferFailedException("잘못된 계좌 금액 설정입니다.");
            if (amount > source.getOneTimeLimit())
                throw new TransferLimitExceededException("1회 이체 한도 초과");
            if (source.getBalance() < amount)
                throw new InsufficientBalanceException("잔액 부족");
            if (amount > Long.MAX_VALUE - target.getBalance())
                throw new TransferFailedException("입금 계좌의 저장 가능한 잔액 범위를 초과합니다.");

            // 잠금 획득 뒤 DB 세션의 날짜를 한 번만 확정한다.
            LocalDate businessDay = transactions.currentDate(conn);
            BigDecimal today = transactions.getTransferAmount(conn, source.getAccountId(), businessDay);
            if (today.signum() < 0
                    || today.add(BigDecimal.valueOf(amount))
                    .compareTo(BigDecimal.valueOf(source.getDailyLimit())) > 0)
                throw new TransferLimitExceededException("1일 이체 한도 초과");

            if (accounts.withdraw(conn, source.getAccountId(), amount) != 1
                    || accounts.deposit(conn, target.getAccountId(), amount) != 1)
                throw new TransferFailedException("잔액 변경 실패");

            Transaction transaction = new Transaction(source.getAccountId(), target.getAccountId(),
                    amount, "TRANSFER", "SUCCESS");
            int id = transactions.save(conn, transaction, requestId, userId, businessDay);
            commitStarted = true;
            conn.commit();
            return id;
        } catch (Exception e) {
            boolean rollbackFailed = false;
            if (conn != null) {
                try { conn.rollback(); }
                catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                    rollbackFailed = true;
                }
            }
            if (commitStarted || rollbackFailed)
                throw new TransferOutcomeUnknownException(requestId, e);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException("이체 처리 실패. 같은 요청 ID로 재시도할 수 있습니다.", e);
        } finally {
            // setAutoCommit(true)는 미완료 트랜잭션을 커밋할 수 있으므로 호출하지 않는다.
            // close 실패가 이미 성공한 commit의 결과를 뒤집지 않게 로그만 남긴다.
            DBConnection.close(conn);
        }
    }

    private static Account find(List<Account> accounts, int id) {
        return accounts.stream().filter(a -> a.getAccountId() == id).findFirst()
                .orElseThrow(() -> new AccountNotFoundException("잠금 중 계좌를 찾지 못했습니다."));
    }

    private static void authenticate(Account source, int userId, String password) {
        if (source.getUserId() != userId)
            throw new UnauthorizedAccountAccessException("본인 계좌가 아닙니다.");
        if (!PasswordHasher.matches(password, source.getAccountPassword()))
            throw new InvalidAccountPasswordException("계좌 비밀번호가 일치하지 않습니다.");
    }
}
