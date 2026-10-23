-- Demo login credentials (email = username; new local/demo databases only):
-- ADMIN:      aureliabook.team@gmail.com | DDAGTechAdmin391@
-- MANAGER:    manager@aureliabook.vn     | Manager@123
-- SALE_STAFF: staff@aureliabook.vn       | Staff@123
-- CUSTOMER:   customer@aureliabook.vn    | Customer@123
-- MANAGER:    kho.nguyen@aureliabook.vn  | Manager@123
-- SALE_STAFF: lan.tran@aureliabook.vn    | Staff@123
-- CUSTOMER:   anhntdce200031@gmail.com   | Customer@123
-- Existing accounts keep their current password; re-running does not reset it.
-- These are public test credentials. Production does not load this demo seed.
--
-- LOCAL / DEMO ONLY. Mounted automatically by docker-compose.override.yml.
-- Re-running preserves passwords, roles, profile data and inactive status.
-- Conflicting contacts abort the entire seed; no existing account is promoted.
SET NAMES utf8mb4;
USE aurelia_books_db;

DROP PROCEDURE IF EXISTS seed_demo_users;
DELIMITER //
CREATE PROCEDURE seed_demo_users()
BEGIN
    DECLARE finished BOOLEAN DEFAULT FALSE;
    DECLARE wanted_role VARCHAR(50);
    DECLARE wanted_email VARCHAR(100);
    DECLARE wanted_phone VARCHAR(15);
    DECLARE wanted_hash VARCHAR(255);
    DECLARE wanted_name VARCHAR(100);
    DECLARE wanted_dob DATE;
    DECLARE wanted_gender VARCHAR(10);
    DECLARE role_key BIGINT;
    DECLARE email_owner BIGINT;
    DECLARE phone_owner BIGINT;
    DECLARE existing_role BIGINT;
    DECLARE conflict_message VARCHAR(128);
    DECLARE accounts CURSOR FOR
        SELECT 'ROLE_ADMIN', 'aureliabook.team@gmail.com', '0988888888', '$2a$12$V7bsA/S/UGI4w1AF9lPk9.PDh.O2sefnI.RuHuxFR8WVvZgGoFgVK', 'DDAGTech Demo Admin', NULL, 'OTHER'
        UNION ALL SELECT 'ROLE_MANAGER', 'manager@aureliabook.vn', '0977777777', '$2a$12$miXP/3RJoDMt.2J/HMs8VOJw42cX448doR6uwAKHwXdTEJPaI66Gy', 'DDAGTech Demo Manager', NULL, 'OTHER'
        UNION ALL SELECT 'ROLE_SALE_STAFF', 'staff@aureliabook.vn', '0966666666', '$2a$12$OvZFsunlEIoSvxSDKpPJ/eb1/66LJ5GofJAciv.3z39zXiIz6W6Ge', 'DDAGTech Demo Staff', NULL, 'OTHER'
        UNION ALL SELECT 'ROLE_CUSTOMER', 'customer@aureliabook.vn', '0944444444', '$2a$12$Ex2xqIZAmnmR5nVgsNxBjuOPlep85VyAf5gt3WGL5zdEjU5LjKaMq', 'DDAGTech Demo Customer', NULL, 'OTHER'
        -- Supplementary fixtures added on main; kept in the same safe demo-only seed.
        UNION ALL SELECT 'ROLE_MANAGER', 'kho.nguyen@aureliabook.vn', '0971111111', '$2a$12$miXP/3RJoDMt.2J/HMs8VOJw42cX448doR6uwAKHwXdTEJPaI66Gy', 'Nguyễn Văn Kho (Manager)', '1991-03-10', 'MALE'
        UNION ALL SELECT 'ROLE_SALE_STAFF', 'lan.tran@aureliabook.vn', '0961111111', '$2a$12$OvZFsunlEIoSvxSDKpPJ/eb1/66LJ5GofJAciv.3z39zXiIz6W6Ge', 'Trần Thị Lan (Sale Staff)', '1996-08-25', 'FEMALE'
        UNION ALL SELECT 'ROLE_CUSTOMER', 'anhntdce200031@gmail.com', '0955555555', '$2a$12$Ex2xqIZAmnmR5nVgsNxBjuOPlep85VyAf5gt3WGL5zdEjU5LjKaMq', 'Lê Khách Hàng (Customer)', '1999-09-09', 'OTHER';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = TRUE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;
    IF (SELECT COUNT(*) FROM roles WHERE role_name IN ('ROLE_ADMIN', 'ROLE_MANAGER', 'ROLE_SALE_STAFF', 'ROLE_CUSTOMER')) <> 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Initialize the four RBAC roles before demo accounts.';
    END IF;
    OPEN accounts;
    account_loop: LOOP
        FETCH accounts INTO wanted_role, wanted_email, wanted_phone, wanted_hash, wanted_name, wanted_dob, wanted_gender;
        IF finished THEN
            LEAVE account_loop;
        END IF;
        -- The ADMIN role is locked first and held until commit, serializing seed/bootstrap.
        SELECT id INTO role_key FROM roles WHERE role_name = wanted_role FOR UPDATE;
        SET email_owner = (SELECT id FROM users WHERE email = wanted_email FOR UPDATE);
        SET phone_owner = (SELECT id FROM users WHERE phone = wanted_phone FOR UPDATE);
        IF email_owner IS NOT NULL THEN
            SET existing_role = (SELECT role_id FROM users WHERE id = email_owner);
            IF existing_role <> role_key OR (phone_owner IS NOT NULL AND phone_owner <> email_owner) THEN
                SET conflict_message = CONCAT('Demo seed contact/role conflict for ', wanted_role, '; existing accounts unchanged.');
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = conflict_message;
            END IF;
            -- Same identity already exists: leave password, role and active flag untouched.
        ELSEIF phone_owner IS NOT NULL THEN
            SET conflict_message = CONCAT('Demo seed phone conflict for ', wanted_role, '; existing accounts unchanged.');
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = conflict_message;
        ELSE
            INSERT INTO users (role_id, email, phone, password_hash, full_name, dob, gender, auth_provider, is_active)
            VALUES (role_key, wanted_email, wanted_phone, wanted_hash, wanted_name, wanted_dob, wanted_gender, 'LOCAL', TRUE);
        END IF;
    END LOOP;
    CLOSE accounts;
    COMMIT;
END//
DELIMITER ;
CALL seed_demo_users();
DROP PROCEDURE seed_demo_users;
