package service;

import dao.TransactionDAO;
import domain.Transaction;
import java.sql.Timestamp;
import java.time.LocalDate;

public class HistoryService {
    private final TransactionDAO transactions = new TransactionDAO();
    private final AccountService accounts = new AccountService();

    public TransactionDAO.Page getPage(int accountId, int userId, LocalDate start,
                                      LocalDate endInclusive, TransactionDAO.Cursor cursor, int size) {
        accounts.getAccountDetail(accountId, userId);
        if ((start == null) != (endInclusive == null))
            throw new IllegalArgumentException("시작일과 종료일을 함께 입력하세요.");
        if (start != null && start.isAfter(endInclusive))
            throw new IllegalArgumentException("시작일이 종료일보다 늦습니다.");
        return transactions.findPage(accountId,
                start == null ? null : Timestamp.valueOf(start.atStartOfDay()),
                endInclusive == null ? null : Timestamp.valueOf(endInclusive.plusDays(1).atStartOfDay()),
                cursor, size);
    }

    public Transaction getTransactionDetail(int transactionId, int accountId, int userId) {
        accounts.getAccountDetail(accountId, userId);
        Transaction t = transactions.findById(transactionId);
        if (t == null || (t.getFromAccountId() != accountId && t.getToAccountId() != accountId))
            throw new IllegalArgumentException("해당 계좌의 거래가 아닙니다.");
        return t;
    }

    public String getDirection(int id, Transaction t) {
        if (t.getFromAccountId() == id) return "출금";
        if (t.getToAccountId() == id) return "입금";
        throw new IllegalArgumentException("해당 계좌의 거래가 아닙니다.");
    }
}
