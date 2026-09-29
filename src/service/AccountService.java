package service;

import dao.AccountDAO;
import util.PasswordHasher;
import domain.Account;
import exception.AccountNotFoundException;
import exception.InsufficientBalanceException;
import exception.InvalidAccountPasswordException;
import exception.InvalidAccountStatusException;
import exception.TransferLimitExceededException;
import exception.UnauthorizedAccountAccessException;

import java.util.List;
import java.util.Random;

public class AccountService {

    private final AccountDAO accountDAO = new AccountDAO();

    // 1. 계좌 생성
    public int createAccount(int userId, String accountPassword) {
        if (userId <= 0) {
            throw new IllegalArgumentException("잘못된 사용자입니다.");
        }

        if (accountPassword == null || accountPassword.isBlank()) {
            throw new IllegalArgumentException("계좌 비밀번호를 입력하세요.");
        }

        String accountNumber = generateAccountNumber();

        while (accountDAO.existsByAccountNumber(accountNumber)) {
            accountNumber = generateAccountNumber();
        }

        Account account = new Account(userId, accountNumber, 0L, PasswordHasher.hash(accountPassword),
                1000000L, 5000000L, "ACTIVE");

        return accountDAO.save(account);
    }

    // 2. 내 계좌 목록 조회
    public List<Account> getMyAccounts(int userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("잘못된 사용자입니다.");
        }

        return accountDAO.findByUserId(userId);
    }

    // 3. 계좌 상세 조회
    public Account getAccountDetail(int accountId, int loginUserId) {
        Account account = accountDAO.findById(accountId);

        if (account == null) {
            throw new AccountNotFoundException("존재하지 않는 계좌입니다.");
        }

        if (!accountDAO.isOwner(accountId, loginUserId)) {
            throw new UnauthorizedAccountAccessException("본인 계좌만 조회할 수 있습니다.");
        }

        return account;
    }

    // 4. 계좌 존재 여부 확인 - accountId
    public Account getAccountById(int accountId) {
        Account account = accountDAO.findById(accountId);

        if (account == null) {
            throw new AccountNotFoundException("존재하지 않는 계좌입니다.");
        }

        return account;
    }

    // 5. 계좌 존재 여부 확인 - accountNumber
    public Account getAccountByNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("계좌번호를 입력하세요.");
        }

        Account account = accountDAO.findByAccountNumber(accountNumber);

        if (account == null) {
            throw new AccountNotFoundException("존재하지 않는 계좌입니다.");
        }

        return account;
    }

    // 6. 계좌 소유자 확인
    public void validateAccountOwner(int accountId, int loginUserId) {
        boolean isOwner = accountDAO.isOwner(accountId, loginUserId);

        if (!isOwner) {
            throw new UnauthorizedAccountAccessException("본인 계좌가 아닙니다.");
        }
    }

    // 7. 계좌 상태 확인
    public void validateAccountStatus(int accountId) {
        String status = accountDAO.getStatus(accountId);

        if (status == null) {
            throw new AccountNotFoundException("존재하지 않는 계좌입니다.");
        }

        if (!status.equals("ACTIVE")) {
            throw new InvalidAccountStatusException("정상 상태의 계좌가 아닙니다.");
        }
    }

    // 8. 계좌 비밀번호 확인
    public void validateAccountPassword(int accountId, String inputPassword) {
        if (inputPassword == null || inputPassword.isBlank()) {
            throw new IllegalArgumentException("계좌 비밀번호를 입력하세요.");
        }

        String savedPassword = accountDAO.getAccountPassword(accountId);

        if (savedPassword == null) {
            throw new AccountNotFoundException("존재하지 않는 계좌입니다.");
        }

        if (!PasswordHasher.matches(inputPassword, savedPassword)) {
            throw new InvalidAccountPasswordException("계좌 비밀번호가 일치하지 않습니다.");
        }
    }

    // 9. 잔액 확인
    public void validateSufficientBalance(int accountId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        }

        long balance = accountDAO.getBalance(accountId);

        if (balance < 0) {
            throw new AccountNotFoundException("존재하지 않는 계좌입니다.");
        }

        if (balance < amount) {
            throw new InsufficientBalanceException("잔액이 부족합니다.");
        }
    }

    // 10. 1회 이체 한도 확인
    public void validateOneTimeLimit(int accountId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        }

        long oneTimeLimit = accountDAO.getOneTimeLimit(accountId);

        if (oneTimeLimit < 0) {
            throw new AccountNotFoundException("존재하지 않는 계좌입니다.");
        }

        if (amount > oneTimeLimit) {
            throw new TransferLimitExceededException("1회 이체 한도를 초과했습니다.");
        }
    }

    // 11. 1일 이체 한도 조회
    public long getDailyLimit(int accountId) {
        long dailyLimit = accountDAO.getDailyLimit(accountId);

        if (dailyLimit < 0) {
            throw new AccountNotFoundException("존재하지 않는 계좌입니다.");
        }

        return dailyLimit;
    }

    // 계좌번호 생성
    private String generateAccountNumber() {
        Random random = new Random();

        int middle = random.nextInt(99) + 1;
        int last = random.nextInt(1000000);

        return String.format("101-%02d-%06d", middle, last);
    }
}
