package service;

import dao.UserDAO;
import util.PasswordHasher;
import domain.User;
import exception.DuplicateLoginIdException;
import exception.UserNotFoundException;

public class UserService {

    private final UserDAO userDAO = new UserDAO();

    // 1. 회원가입
    public void signup(String loginId, String password, String name) {

        // 1) 홈화면
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("아이디를 입력하세요.");
        }

        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("비밀번호를 입력하세요.");
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름을 입력하세요.");
        }

        // 2) 중복 아이디 검사
        if (userDAO.existsByLoginId(loginId)) {
            throw new DuplicateLoginIdException("이미 사용 중인 아이디입니다.");
        }

        // 3) 사용자 생성
        User user = new User(loginId, PasswordHasher.hash(password), name);

        // 4) DB 저장
        userDAO.save(user);
    }

    // 2. 사용자 조회
    public User getUser(int userId) {

        User user = userDAO.findById(userId);

        if (user == null) {
            throw new UserNotFoundException("사용자를 찾을 수 없습니다.");
        }

        return user;
    }
}
