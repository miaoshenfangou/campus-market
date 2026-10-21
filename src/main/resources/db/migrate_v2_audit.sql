-- ============================================================
--  增量升级脚本 v2 · 审核与下架功能
-- ------------------------------------------------------------
--  适用场景:数据库已经按旧版 schema.sql 初始化过,不能 drop 重建
--  (线上已有真实数据),只需要补字段。
--
--  特点:幂等。可重复执行,已存在的字段会跳过。
--  MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,所以用
--  information_schema + PREPARE 做条件判断。
--
--  执行方式:
--    docker exec -i csh-mysql mysql -uroot -p"$PW" campus_second_hand < migrate_v2_audit.sql
--  或直接 bash deploy.sh deploy(deploy 脚本会自动执行本文件)
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. user.role:0 普通用户,1 审核员
-- ------------------------------------------------------------
SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `user` ADD COLUMN `role` TINYINT NOT NULL DEFAULT 0 COMMENT ''角色:0普通用户 1审核员'' AFTER `school`',
        'SELECT ''user.role 已存在,跳过'' AS msg'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. product:审核备注 / 审核时间 / 审核员
-- ------------------------------------------------------------
SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `product` ADD COLUMN `audit_remark` VARCHAR(255) DEFAULT NULL COMMENT ''审核备注(驳回原因)'' AFTER `view_count`',
        'SELECT ''product.audit_remark 已存在,跳过'' AS msg'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'audit_remark'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `product` ADD COLUMN `audit_time` DATETIME DEFAULT NULL COMMENT ''审核时间'' AFTER `audit_remark`',
        'SELECT ''product.audit_time 已存在,跳过'' AS msg'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'audit_time'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `product` ADD COLUMN `auditor_id` INT UNSIGNED DEFAULT NULL COMMENT ''审核员用户ID'' AFTER `audit_time`',
        'SELECT ''product.auditor_id 已存在,跳过'' AS msg'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'auditor_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. 新发布的商品改为「待审核」:调整默认值
--    (已有记录的 status 不变,线上已上架的商品继续可见)
-- ------------------------------------------------------------
ALTER TABLE `product` MODIFY COLUMN `status` TINYINT DEFAULT 0
    COMMENT '状态:0待审核 1已上架 2已下架 3已售出 4审核驳回';

-- ------------------------------------------------------------
-- 4. 指定审核员:xiaowen 兼任
--    需要换人时,把这里的用户名改成你的账号即可
-- ------------------------------------------------------------
UPDATE `user` SET `role` = 1 WHERE `username` = 'xiaowen';

-- ------------------------------------------------------------
-- 5. 造一条待审核数据,方便验证审核台(仅当没有待审核商品时才插入)
-- ------------------------------------------------------------
INSERT INTO `product` (`user_id`, `category_id`, `title`, `description`, `price`, `original_price`, `status`, `view_count`)
SELECT 2, 6, '雅马哈 F310 民谣吉他',
       '大一社团买的,弹了半年就吃灰了。琴颈正常,带琴包和拨片,弦是上个月新换的。',
       320.00, 780.00, 0, 12
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `product` WHERE `status` = 0);

-- 给刚插入的待审核商品配一张占位主图(没有图片时列表会退化成 emoji)
INSERT INTO `product_image` (`product_id`, `image_url`, `sort_order`)
SELECT p.id, '/product-images/guitar.jpg', 0
FROM `product` p
WHERE p.`title` = '雅马哈 F310 民谣吉他'
  AND NOT EXISTS (SELECT 1 FROM `product_image` pi WHERE pi.product_id = p.id);

SELECT '✅ 升级完成:user.role / product.audit_* 已就绪' AS result;
