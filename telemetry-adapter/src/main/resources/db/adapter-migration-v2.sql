USE `iot_adapter`;

ALTER TABLE `iot_protocol_profile`
    ADD COLUMN `message_id_path` VARCHAR(200) DEFAULT NULL AFTER `seq_path`,
    ADD COLUMN `boot_id_path` VARCHAR(200) DEFAULT NULL AFTER `message_id_path`,
    ADD COLUMN `batch_id_path` VARCHAR(200) DEFAULT NULL AFTER `boot_id_path`,
    ADD COLUMN `retransmitted_at_path` VARCHAR(200) DEFAULT NULL AFTER `batch_id_path`,
    ADD COLUMN `max_ack_mode` VARCHAR(30) NOT NULL DEFAULT 'EVIDENCE_ONLY'
        AFTER `retransmitted_at_path`,
    ADD COLUMN `correlation_policy` VARCHAR(40) NOT NULL DEFAULT 'NONE'
        AFTER `max_ack_mode`;
