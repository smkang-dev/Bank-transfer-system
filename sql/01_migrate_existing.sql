-- 기존 DB용. 앱을 종료하고 백업한 후 대상 DB를 Workbench에서 직접 선택하고 실행.
-- MySQL 8.0/8.4 + InnoDB 기준. DDL은 자동 커밋되므로 전체 rollback 불가.
-- 아래 변경은 1회용. 이미 적용한 열/인덱스가 있다면 SHOW CREATE TABLE로 대조 후 해당 줄 제외.
ALTER TABLE users ENGINE=InnoDB;
ALTER TABLE accounts ENGINE=InnoDB;
ALTER TABLE transactions ENGINE=InnoDB;
ALTER TABLE users
  MODIFY password VARCHAR(255) NOT NULL,
  ADD COLUMN password_encoding VARCHAR(30) NOT NULL DEFAULT 'plain';
ALTER TABLE accounts
  MODIFY balance BIGINT NOT NULL DEFAULT 0,
  MODIFY one_time_limit BIGINT NOT NULL DEFAULT 1000000,
  MODIFY daily_limit BIGINT NOT NULL DEFAULT 5000000,
  MODIFY account_password VARCHAR(255) NOT NULL,
  ADD COLUMN password_encoding VARCHAR(30) NOT NULL DEFAULT 'plain';
ALTER TABLE transactions
  MODIFY amount BIGINT NOT NULL,
  ADD COLUMN request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN request_user_id INT NULL,
  ADD COLUMN business_date DATE NULL;
UPDATE transactions SET business_date=DATE(t_created_at) WHERE business_date IS NULL;
ALTER TABLE transactions MODIFY business_date DATE NOT NULL;
CREATE UNIQUE INDEX uq_transactions_request ON transactions(request_id);
CREATE INDEX idx_transactions_to_created ON transactions(to_account_id,t_created_at);
CREATE INDEX idx_transactions_from_business ON transactions(from_account_id,business_date);
-- 기존 idx_transactions_from_created는 유지한다.
-- 이제 main.MigratePasswords를 실행한다. 기존 평문 값을 해시로 변환하며 암호를 출력하지 않는다.
