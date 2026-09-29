package dao;

import domain.Transaction;
import util.DBConnection;
import util.ConnectionFactory;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class TransactionDAO {
    private final ConnectionFactory connections;
    public TransactionDAO() { this(DBConnection::getConnection); }
    public TransactionDAO(ConnectionFactory connections) { this.connections=connections; }
    public record RequestRecord(int transactionId, int userId, int fromId, int toId, long amount) {}
    public record Cursor(Timestamp createdAt, int id) {}
    public record Page(List<Transaction> items, boolean hasMore) {}

    public int save(Connection conn, Transaction t, String requestId, int userId, LocalDate day) {
        String sql = "INSERT INTO transactions "
                + "(from_account_id,to_account_id,amount,type,t_status,request_id,request_user_id,business_date) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, t.getFromAccountId());
            ps.setInt(2, t.getToAccountId());
            ps.setLong(3, t.getAmount());
            ps.setString(4, t.getType());
            ps.setString(5, t.getStatus());
            ps.setString(6, requestId);
            ps.setInt(7, userId);
            ps.setDate(8, Date.valueOf(day));
            if (ps.executeUpdate() != 1) throw new SQLException("거래 기록 저장 실패");
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("거래 ID 반환 실패");
            }
        } catch (SQLException e) {
            throw new RuntimeException("거래 기록 저장 실패. 같은 요청 ID로 재확인하세요.", e);
        }
    }

    public RequestRecord findByRequestId(Connection conn, String requestId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT transaction_id,request_user_id,from_account_id,to_account_id,amount "
                        + "FROM transactions WHERE request_id=? AND type='TRANSFER' AND t_status='SUCCESS'")) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new RequestRecord(rs.getInt(1), rs.getInt(2),
                        rs.getInt(3), rs.getInt(4), rs.getLong(5)) : null;
            }
        } catch (SQLException e) { throw new RuntimeException("요청 결과 조회 실패", e); }
    }

    public LocalDate currentDate(Connection conn) {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT CURDATE()")) {
            rs.next();
            return rs.getDate(1).toLocalDate();
        } catch (SQLException e) { throw new RuntimeException("영업일 조회 실패", e); }
    }

    public BigDecimal getTransferAmount(Connection conn, int id, LocalDate day) {
        // 합계는 BIGINT보다 클 수 있으므로 BigDecimal로 받는다.
        String sql = "SELECT COALESCE(SUM(amount),0) FROM transactions "
                + "WHERE from_account_id=? AND business_date=? "
                + "AND type='TRANSFER' AND t_status='SUCCESS'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setDate(2, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
        } catch (SQLException e) { throw new RuntimeException("일일 이체 합계 조회 실패", e); }
    }

    public Page findPage(int accountId, Timestamp start, Timestamp endExclusive,
                         Cursor cursor, int size) {
        if (size < 1 || size > 200) throw new IllegalArgumentException("페이지 크기는 1~200");
        if ((start == null) != (endExclusive == null))
            throw new IllegalArgumentException("기간의 시작·끝이 함께 필요합니다.");
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM transactions WHERE (from_account_id=? OR to_account_id=?)");
        if (start != null) sql.append(" AND t_created_at>=? AND t_created_at<?");
        if (cursor != null) sql.append(
                " AND (t_created_at<? OR (t_created_at=? AND transaction_id<?))");
        sql.append(" ORDER BY t_created_at DESC,transaction_id DESC LIMIT ?");
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setInt(i++, accountId);
            ps.setInt(i++, accountId);
            if (start != null) {
                ps.setTimestamp(i++, start);
                ps.setTimestamp(i++, endExclusive);
            }
            if (cursor != null) {
                ps.setTimestamp(i++, cursor.createdAt());
                ps.setTimestamp(i++, cursor.createdAt());
                ps.setInt(i++, cursor.id());
            }
            ps.setInt(i, size + 1);
            List<Transaction> items = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(toTransaction(rs));
            }
            boolean more = items.size() > size;
            if (more) items.remove(items.size() - 1);
            return new Page(List.copyOf(items), more);
        } catch (SQLException e) { throw new RuntimeException("거래내역 조회 실패", e); }
    }

    public Transaction findById(int id) {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM transactions WHERE transaction_id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? toTransaction(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException("거래 상세 조회 실패", e); }
    }

    private Transaction toTransaction(ResultSet rs) throws SQLException {
        return new Transaction(rs.getInt("transaction_id"), rs.getInt("from_account_id"),
                rs.getInt("to_account_id"), rs.getLong("amount"), rs.getString("type"),
                rs.getString("t_status"), rs.getTimestamp("t_created_at"));
    }
}
