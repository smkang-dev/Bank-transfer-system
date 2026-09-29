package service;

import dao.UserDAO;
import util.PasswordHasher;
import domain.User;
import exception.UserNotFoundException;

public class LoginService {
    private final UserDAO userDAO = new UserDAO();
    public User login(String loginId, String password) {

        // 1.입력
        if (loginId == null || loginId.isEmpty()) {
            throw new IllegalArgumentException("아이디를 입력하세요.");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("비밀번호를 입력하세요.");
        }

        // 2. 사용자 조회
        User user = userDAO.findByLoginId(loginId);

        // 3. 사용자 존재 확인
        if (user == null) {
            throw new UserNotFoundException("존재하지 않는 아이디입니다.");
        }

        // 4. 비밀번호 일치 확인
        if (!PasswordHasher.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        return user;
    }
}

