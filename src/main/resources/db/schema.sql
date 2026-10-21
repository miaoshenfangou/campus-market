-- ============================================================
--  校园二手交易平台 · 数据库初始化脚本
--  数据库:campus_second_hand
--  字符集:utf8mb4(支持 emoji 与生僻字)
--  说明:按实体类字段反推,含索引设计与初始化数据
-- ============================================================

-- 必须显式声明:MySQL 官方镜像的初始化容器里 LANG=C,
-- mysql 客户端会把连接字符集判成 latin1,导致中文种子数据入库即乱码。
-- 放在最前面,后续所有 INSERT 才会按 utf8mb4 解释。
SET NAMES utf8mb4;

DROP DATABASE IF EXISTS `campus_second_hand`;
CREATE DATABASE `campus_second_hand`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;
USE `campus_second_hand`;

-- ------------------------------------------------------------
-- 1. 用户表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`          INT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`    VARCHAR(50)  NOT NULL                COMMENT '用户名(登录账号)',
    `password`    VARCHAR(100) NOT NULL                COMMENT '密码(BCrypt 哈希,60位左右,预留100)',
    `email`       VARCHAR(100) DEFAULT NULL            COMMENT '邮箱',
    `phone`       VARCHAR(20)  DEFAULT NULL            COMMENT '手机号',
    `avatar`      VARCHAR(255) DEFAULT NULL            COMMENT '头像URL',
    `real_name`   VARCHAR(50)  DEFAULT NULL            COMMENT '真实姓名',
    `student_id`  VARCHAR(30)  DEFAULT NULL            COMMENT '学号',
    `school`      VARCHAR(100) DEFAULT NULL            COMMENT '学校',
    `role`        TINYINT      NOT NULL DEFAULT 0      COMMENT '角色:0普通用户 1审核员',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 登录名唯一:既是业务约束,也让登录查询走唯一索引(等值查询 O(log n))
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_phone` (`phone`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ------------------------------------------------------------
-- 2. 商品分类表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `name`        VARCHAR(50)     NOT NULL                COMMENT '分类名称',
    `parent_id`   BIGINT UNSIGNED DEFAULT 0               COMMENT '父分类ID,0表示一级分类',
    `level`       INT             DEFAULT 1               COMMENT '层级:1一级 2二级',
    `sort`        INT             DEFAULT 0               COMMENT '排序权重,越小越靠前',
    `create_time` DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_parent_sort` (`parent_id`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品分类表';

-- ------------------------------------------------------------
-- 3. 商品表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `user_id`          BIGINT UNSIGNED  NOT NULL                COMMENT '卖家用户ID',
    `category_id`      BIGINT UNSIGNED  DEFAULT NULL            COMMENT '分类ID',
    `title`            VARCHAR(100)     NOT NULL                COMMENT '商品标题',
    `description`      TEXT             DEFAULT NULL            COMMENT '商品描述',
    `price`            DECIMAL(10, 2)   NOT NULL                COMMENT '售价',
    `original_price`   DECIMAL(10, 2)   DEFAULT NULL            COMMENT '原价',
    `status`           TINYINT          DEFAULT 0               COMMENT '状态:0待审核 1已上架 2已下架 3已售出 4审核驳回',
    `is_recommend`     TINYINT          DEFAULT 0               COMMENT '是否推荐:0否 1是',
    `recommend_weight` INT              DEFAULT 0               COMMENT '推荐权重,越大越靠前',
    `view_count`       INT              DEFAULT 0               COMMENT '浏览次数',
    `audit_remark`     VARCHAR(255)     DEFAULT NULL            COMMENT '审核备注(驳回原因)',
    `audit_time`       DATETIME         DEFAULT NULL            COMMENT '审核时间',
    `auditor_id`       INT UNSIGNED     DEFAULT NULL            COMMENT '审核员用户ID',
    `create_time`      DATETIME         DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 列表页最常见的组合:WHERE status = 1 ORDER BY create_time DESC
    -- 把 status 放前面做等值过滤,create_time 放后面直接满足排序,避免 filesort
    KEY `idx_status_create` (`status`, `create_time`),
    -- 筛选栏的「分类 + 上架状态 + 时间」组合查询
    KEY `idx_category_status` (`category_id`, `status`, `create_time`),
    -- 个人中心「我发布的商品」
    KEY `idx_user_create` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品表';

-- ------------------------------------------------------------
-- 4. 商品图片表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `product_image`;
CREATE TABLE `product_image` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '图片ID',
    `product_id` BIGINT UNSIGNED NOT NULL                COMMENT '商品ID',
    `image_url`  VARCHAR(255)    NOT NULL                COMMENT '图片访问路径',
    `sort_order` INT             DEFAULT 0               COMMENT '排序号,0 为主图',
    PRIMARY KEY (`id`),
    -- 查某商品图片时按 product_id 过滤、sort_order 排序,联合索引可覆盖
    KEY `idx_product_sort` (`product_id`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品图片表';

-- ------------------------------------------------------------
-- 5. 收藏表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `favorite`;
CREATE TABLE `favorite` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
    `user_id`     INT UNSIGNED    NOT NULL                COMMENT '用户ID',
    `product_id`  BIGINT UNSIGNED NOT NULL                COMMENT '商品ID',
    `create_time` DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    -- 联合唯一索引:从数据库层面保证「一个用户对同一商品只能收藏一次」。
    -- 即使并发请求同时通过了应用层的存在性检查,这里也只会有一条写入成功。
    UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收藏表';

-- ------------------------------------------------------------
-- 6. 轮播图表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `banner`;
CREATE TABLE `banner` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '轮播图ID',
    `title`       VARCHAR(100)    DEFAULT NULL            COMMENT '横幅标题',
    `image`       VARCHAR(255)    NOT NULL                COMMENT '图片路径',
    `url`         VARCHAR(255)    DEFAULT NULL            COMMENT '跳转链接',
    `sort`        INT             DEFAULT 0               COMMENT '排序权重,越大越靠前',
    `status`      TINYINT         DEFAULT 1               COMMENT '状态:0禁用 1启用',
    `create_time` DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_sort` (`status`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轮播图表';

-- ============================================================
--  初始化数据
-- ============================================================

-- 分类
INSERT INTO `category` (`id`, `name`, `parent_id`, `level`, `sort`) VALUES
    (1, '数码电子', 0, 1, 1),
    (2, '教材书籍', 0, 1, 2),
    (3, '生活用品', 0, 1, 3),
    (4, '运动器材', 0, 1, 4),
    (5, '服饰鞋包', 0, 1, 5),
    (6, '其他',     0, 1, 6);

-- 轮播图
INSERT INTO `banner` (`title`, `image`, `url`, `sort`, `status`) VALUES
    ('让每一件闲置都找到新主人', '/banner/banner1.jpg', '/product/list', 100, 1),
    ('开学季 · 教材书籍专区',    '/banner/banner2.jpg', '/product/list?categoryId=2', 90, 1),
    ('数码好物限时出',           '/banner/banner3.jpg', '/product/list?categoryId=1', 80, 1);

-- 测试用户
-- 密码统一为 123456,下面是 BCrypt 加密后的结果
-- 可用 PasswordGenerator 重新生成:new BCryptPasswordEncoder().encode("123456")
-- role:0 普通用户,1 审核员。xiaowen 兼任审核员,登录后导航栏会多出「审核台」
INSERT INTO `user` (`username`, `password`, `email`, `phone`, `real_name`, `school`, `student_id`, `role`) VALUES
    ('xiaowen',  '$2a$10$DKyBr1/cHB2IdP8MeaPqte2snpL24SpZX7wR2WW7lIK85pGkibik.', 'wenweichao@163.com', '19213448358', '温伟超', '西安翻译学院', '2023001', 1),
    ('azhe',     '$2a$10$kz3P3lpi8GjBAAymwoUdm.HqY9CqdagGXqXbc4ET2ENBOv.ZZrV7K', 'azhe@163.com',       '13800000002', '阿泽',   '西安翻译学院', '2023002', 0),
    ('lina',     '$2a$10$gcWVjTDMdK/2rH64MJlRuusgt4sogNTWesrLLuZiizXqkbkt0QXXW', 'lina@163.com',       '13800000003', 'Lina',   '西安翻译学院', '2023003', 0);

-- 测试商品
INSERT INTO `product` (`user_id`, `category_id`, `title`, `description`, `price`, `original_price`, `status`, `view_count`) VALUES
    (1, 1, '九成新 Kindle Paperwhite 4 电子书阅读器', '大二买的,使用不到一年,带原装保护套,无划痕无死点。因改用 iPad 看书所以出,可当面验货。', 520.00, 998.00,  1, 386),
    (2, 1, '罗技 MX Master 3 无线鼠标',              '去年双十一入手,用了两个月换回有线鼠标,一直闲置。多设备切换很顺滑。',                 380.00, 699.00,  1, 254),
    (3, 2, '考研数学复习全书(张宇)+ 660 题',       '上岸了,全套出,有少量笔记和划线,不影响阅读。价格可小刀。',                              45.00, 158.00,  1, 512),
    (1, 1, '索尼 WH-1000XM4 头戴降噪耳机',            '降噪效果很好,图书馆学习神器。因换了新耳机闲置,包装盒和说明书都在。',                   890.00, 1899.00, 1, 431),
    (2, 4, '尤尼克斯羽毛球拍 天斧 88D',               '打了半年,已换新拍线 25 磅,手胶新换。附赠拍套和四只羽毛球。',                          620.00, 1280.00, 1, 298),
    (3, 3, '宿舍小冰箱 50L 静音款',                   '用了一年半,制冷正常,外形有轻微磕碰痕迹。搬宿舍不方便所以出,自提。',                   260.00, 599.00,  1, 342),
    -- 下面这条是「待审核」,用于演示审核流程:登录 xiaowen 后进「审核台」可看到
    (2, 6, '雅马哈 F310 民谣吉他',                    '大一社团买的,弹了半年就吃灰了。琴颈正常,带琴包和拨片,弦是上个月新换的。',            320.00, 780.00,  0, 12);

-- 商品图片(每件商品一张主图,sort_order = 0)
INSERT INTO `product_image` (`product_id`, `image_url`, `sort_order`) VALUES
    (1, '/product-images/kindle.jpg',   0),
    (2, '/product-images/mouse.jpg',    0),
    (3, '/product-images/books.jpg',    0),
    (4, '/product-images/headset.jpg',  0),
    (5, '/product-images/racket.jpg',   0),
    (6, '/product-images/fridge.jpg',   0),
    (7, '/product-images/guitar.jpg',   0);

-- 收藏记录
INSERT INTO `favorite` (`user_id`, `product_id`) VALUES
    (1, 3),
    (1, 5),
    (2, 1);

-- ============================================================
--  验证索引是否生效(在 Navicat 里执行,看 type / key / rows 列)
-- ============================================================
-- EXPLAIN SELECT * FROM product
--   WHERE status = 1 AND category_id = 1
--   ORDER BY create_time DESC LIMIT 10;
-- 预期:type=ref,key=idx_category_status,Extra 中不出现 Using filesort
