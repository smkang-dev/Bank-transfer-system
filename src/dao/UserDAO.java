package dao;

import domain.User;
import util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class UserDAO {

    // 1. login_id 중복 확인
    public boolean existsByLoginId(String loginId) {
        String sql = "SELECT 1 FROM users WHERE login_id = ?";

        try (
                Connection conn = DBConnection.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)
        ) {
            pstmt.setString(1, loginId);

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            throw new RuntimeException("login_id 중복 확인 중 오류가 발생했습니다.", e);
        }
    }

    // 2. 회원 저장
    public int save(User user) {
        String sql = "INSERT INTO users (login_id, password, name, password_encoding) VALUES (?, ?, ?, 'pbkdf2-sha256')";

        try (
                Connection conn = DBConnection.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)
        ) {
            pstmt.setString(1, user.getLoginId());
            pstmt.setString(2, user.getPassword());
            pstmt.setString(3, user.getName());

            int affectedRows = pstmt.executeUpdate();

            if (affectedRows == 0) {
                throw new RuntimeException("사용자 저장에 실패했습니다.");
            }

            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1); // 생성된 user_id 반환
                } else {
                    throw new RuntimeException("생성된 user_id를 가져오지 못했습니다.");
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("사용자 저장 중 오류가 발생했습니다.", e);
        }
    }

    // 3. login_id로 사용자 조회
    public User findByLoginId(String loginId) {
        String sql = "SELECT user_id, login_id, password, name, password_encoding FROM users WHERE login_id = ?";

        try (
                Connection conn = DBConnection.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)
        ) {
            pstmt.setString(1, loginId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToUser(rs);
                }
                return null;
            }

        } catch (SQLException e) {
            throw new RuntimeException("login_id로 사용자 조회 중 오류가 발생했습니다.", e);
        }
    }

    // 4. user_id로 사용자 조회
    public User findById(int userId) {
        String sql = "SELECT user_id, login_id, password, name, password_encoding FROM users WHERE user_id = ?";

        try (
                Connection conn = DBConnection.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)
        ) {
            pstmt.setInt(1, userId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToUser(rs);
                }
                return null;
            }

        } catch (SQLException e) {
            throw new RuntimeException("user_id로 사용자 조회 중 오류가 발생했습니다.", e);
        }
    }

    // ResultSet -> User 매핑
    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        if (!"pbkdf2-sha256".equals(rs.getString("password_encoding"))) {
            throw new SQLException("기존 회원 비밀번호 변환을 먼저 실행하세요.");
        }
        return new User(
                rs.getInt("user_id"),
                rs.getString("login_id"),
                rs.getString("password"),
                rs.getString("name")
        );
    }
}
