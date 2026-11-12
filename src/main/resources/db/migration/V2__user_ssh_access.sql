-- Accès VPS (tunnel SSH) par utilisateur : colonnes users.ssh_* + valeur d'enum SSH_ACCESS du journal admin.
-- Idempotent (rejouable) : DDL conditionné par information_schema (MySQL 8 / MariaDB 10.11).

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'ssh_status');
SET @s := IF(@c = 0, "ALTER TABLE users ADD COLUMN ssh_status enum ('ACTIVE','DISABLED','ERROR','NONE','PENDING_KEY') NOT NULL DEFAULT 'NONE'", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'ssh_message');
SET @s := IF(@c = 0, "ALTER TABLE users ADD COLUMN ssh_message varchar(255) NULL", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'ssh_updated_at');
SET @s := IF(@c = 0, "ALTER TABLE users ADD COLUMN ssh_updated_at datetime(6) NULL", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- MODIFY avec la liste complète : naturellement rejouable.
ALTER TABLE admin_action_log MODIFY COLUMN action enum ('ARM_BINDING','AUTO_REENGAGE','BULK','CREDENTIAL_ENABLED','CREDENTIAL_STORED','DOCTOR','KILL_SWITCH','LIMITS','MODULATION','ORDER_READ','ORDER_SEND','ORDER_VALIDATE','PLAN','PRESET','PROVIDER','RECONCILE','RUN','SSH_ACCESS') NOT NULL;
