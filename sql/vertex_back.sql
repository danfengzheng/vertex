-- MySQL dump 10.13  Distrib 8.0.41, for Linux (x86_64)
--
-- Host: localhost    Database: vertex
-- ------------------------------------------------------
-- Server version	8.0.41

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `chn_alert_rule`
--

DROP TABLE IF EXISTS `chn_alert_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chn_alert_rule` (
  `id` bigint NOT NULL COMMENT 'ID',
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `chain` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'ALL' COMMENT ' BNB/SOLANA/ALL',
  `min_score` int DEFAULT '60' COMMENT ' 0-100',
  `min_market_cap_usd` decimal(24,4) DEFAULT NULL COMMENT 'USD',
  `min_liquidity_usd` decimal(24,4) DEFAULT NULL COMMENT 'USD',
  `min_holder_count` int DEFAULT NULL,
  `max_top10_holder_pct` decimal(10,4) DEFAULT NULL COMMENT 'Top10%',
  `notify_channels` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '["telegram"]' COMMENT 'JSON',
  `require_liquidity_locked` tinyint DEFAULT NULL COMMENT ' NULL=',
  `enabled` tinyint DEFAULT '1' COMMENT ' 0- 1-',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`,`deleted`),
  KEY `idx_chain` (`chain`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chn_alert_rule`
--

LOCK TABLES `chn_alert_rule` WRITE;
/*!40000 ALTER TABLE `chn_alert_rule` DISABLE KEYS */;
INSERT INTO `chn_alert_rule` VALUES (2027353226234449921,'高分新币','BNB',60,1000.0000,500.0000,20,80.0000,'[\"telegram\"]',0,1,NULL,NULL,'2026-02-27 12:00:50',1,'2026-03-16 12:47:10',0);
/*!40000 ALTER TABLE `chn_alert_rule` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chn_source_config`
--

DROP TABLE IF EXISTS `chn_source_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chn_source_config` (
  `id` bigint NOT NULL,
  `source_id` varchar(32) NOT NULL COMMENT 'bnb_alpha | bnb_trending',
  `source_name` varchar(64) NOT NULL,
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT ' 0- 1-',
  `min_market_cap_usd` double DEFAULT NULL COMMENT ' USD ',
  `max_market_cap_usd` double DEFAULT NULL COMMENT ' USD null=',
  `min_liquidity_usd` double DEFAULT NULL COMMENT ' USD ',
  `min_volume_liquidity_ratio` double DEFAULT NULL COMMENT '/bnb_trending',
  `min_price_change_1h_pct` double DEFAULT NULL COMMENT '1hbnb_trending',
  `page_size` int DEFAULT NULL COMMENT ' API bnb_alpha',
  `scan_limit` int DEFAULT NULL COMMENT 'bnb_trending',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_id` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chn_source_config`
--

LOCK TABLES `chn_source_config` WRITE;
/*!40000 ALTER TABLE `chn_source_config` DISABLE KEYS */;
INSERT INTO `chn_source_config` VALUES (1680000000001,'bnb_alpha','Binance Alpha ',0,100000,NULL,10000,NULL,NULL,50,NULL,NULL,'2026-03-17 03:26:36',1,'2026-04-06 09:43:10',0),(1680000000002,'bnb_trending','BSC DEX ',0,100000,50000000,5000,0.3,2,NULL,50,NULL,'2026-03-17 03:26:36',1,'2026-04-06 09:43:13',0);
/*!40000 ALTER TABLE `chn_source_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chn_token`
--

DROP TABLE IF EXISTS `chn_token`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chn_token` (
  `id` bigint NOT NULL COMMENT 'ID',
  `chain` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ' BNB/SOLANA',
  `contract_address` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '/Mint',
  `symbol` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `decimals` int DEFAULT NULL,
  `total_supply` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `deployer_address` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `deploy_block` bigint DEFAULT NULL,
  `deploy_time` bigint DEFAULT NULL COMMENT '(ms)',
  `score` int DEFAULT '0' COMMENT ' 0-100',
  `score_onchain` int DEFAULT '0' COMMENT ' 0-30',
  `score_market` int DEFAULT '0' COMMENT ' 0-40',
  `score_tokenomics` int DEFAULT '0' COMMENT ' 0-20',
  `score_novelty` int DEFAULT '0' COMMENT ' 0-10',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING' COMMENT ' PENDING/SCORED/ALERTED/IGNORED',
  `alerted` tinyint DEFAULT '0' COMMENT ' 0- 1-',
  `alert_score` int DEFAULT NULL,
  `pair_address` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'DEX',
  `quote_token` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ' WBNB/USDT/SOL',
  `raw_meta` longtext COLLATE utf8mb4_unicode_ci COMMENT 'JSON',
  `data_source` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ' bnb_primary/bnb_alpha/bnb_trending/SOL',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chain_address` (`chain`,`contract_address`,`deleted`),
  KEY `idx_chain` (`chain`),
  KEY `idx_score` (`score`),
  KEY `idx_status` (`status`),
  KEY `idx_deploy_time` (`deploy_time`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_data_source` (`data_source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chn_token`
--

--
-- Table structure for table `chn_token_metrics`
--

DROP TABLE IF EXISTS `chn_token_metrics`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chn_token_metrics` (
  `id` bigint NOT NULL COMMENT 'ID',
  `token_id` bigint NOT NULL COMMENT ' chn_token.id',
  `snapshot_time` bigint NOT NULL COMMENT '(ms)',
  `holder_count` int DEFAULT NULL,
  `tx_count_1h` int DEFAULT NULL COMMENT '1',
  `lp_add_count` int DEFAULT NULL,
  `liquidity_locked` tinyint DEFAULT '0' COMMENT ' 0/1',
  `contract_verified` tinyint DEFAULT '0' COMMENT ' 0/1',
  `price_usd` decimal(30,18) DEFAULT NULL COMMENT 'USD',
  `market_cap_usd` decimal(24,4) DEFAULT NULL COMMENT 'USD',
  `liquidity_usd` decimal(24,4) DEFAULT NULL COMMENT 'USD',
  `volume_24h_usd` decimal(24,4) DEFAULT NULL COMMENT '24h USD',
  `price_change_1h_pct` decimal(10,4) DEFAULT NULL COMMENT '1h%',
  `price_change_24h_pct` decimal(10,4) DEFAULT NULL COMMENT '24h%',
  `buy_pressure_1h` decimal(10,4) DEFAULT NULL COMMENT '1h 0-100',
  `top10_holder_pct` decimal(10,4) DEFAULT NULL COMMENT 'Top10%',
  `deployer_holding_pct` decimal(10,4) DEFAULT NULL COMMENT '%',
  `lp_pool_pct` decimal(10,4) DEFAULT NULL COMMENT 'LP%',
  `age_minutes` int DEFAULT NULL,
  `pump_fun_listed` tinyint DEFAULT '0' COMMENT 'Pump.fun 0/1',
  `bonding_curve_progress` decimal(10,4) DEFAULT NULL COMMENT 'Bonding curve 0-100%NULLDEX',
  `reply_count` int DEFAULT NULL COMMENT 'Pump.fun reply_count / Four.meme commentCount',
  `launchpad_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'pump.fun / four.meme / null=DEX',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_token_id` (`token_id`),
  KEY `idx_snapshot_time` (`snapshot_time`),
  KEY `idx_token_snapshot` (`token_id`,`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chn_token_metrics`
--

--
-- Table structure for table `stg_signal`
--

DROP TABLE IF EXISTS `stg_signal`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stg_signal` (
  `id` bigint NOT NULL COMMENT '信号ID',
  `strategy_id` bigint NOT NULL COMMENT '策略ID',
  `strategy_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '策略名称',
  `symbol` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易对',
  `exchange` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易所',
  `interval` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'K线周期',
  `signal_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '信号类型 BUY/SELL/NEUTRAL',
  `signal_strength` int DEFAULT '0' COMMENT '信号强度 0-100',
  `price` decimal(20,8) DEFAULT NULL COMMENT '触发价格',
  `signal_time` bigint NOT NULL COMMENT '信号时间（K线时间戳）',
  `indicators` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '指标值JSON',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '信号描述',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_strategy_signal_time_type` (`strategy_id`,`signal_time`,`signal_type`),
  KEY `idx_strategy_id` (`strategy_id`),
  KEY `idx_exchange_symbol` (`exchange`,`symbol`),
  KEY `idx_signal_type` (`signal_type`),
  KEY `idx_signal_time` (`signal_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='策略信号表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `stg_signal`
--

--
-- Table structure for table `stg_strategy`
--

DROP TABLE IF EXISTS `stg_strategy`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stg_strategy` (
  `id` bigint NOT NULL COMMENT '策略ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '策略名称',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '策略描述',
  `exchange` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易所',
  `symbol` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易对',
  `interval` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'K线周期',
  `indicator_configs` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '指标配置JSON',
  `enabled` tinyint DEFAULT '0' COMMENT '是否启用 0-禁用 1-启用',
  `auto_trade` tinyint NOT NULL DEFAULT '0' COMMENT '是否开启自动交易 0-否 1-是',
  `min_signal_strength` int DEFAULT NULL COMMENT '(0-100)NULL60',
  `trade_mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '交易模式: AUTO/MANUAL',
  `execution_mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'PAPER' COMMENT '执行模式: LIVE/PAPER',
  `account_id` bigint DEFAULT NULL COMMENT '关联交易账户ID',
  `position_sizing` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'FIXED' COMMENT '仓位计算模式: FIXED/PERCENT',
  `trade_quantity` decimal(30,10) DEFAULT NULL COMMENT '每次交易数量',
  `position_ratio` decimal(5,2) DEFAULT '1.00' COMMENT '仓位比例0-1',
  `initial_capital` decimal(30,10) DEFAULT '10000.0000000000' COMMENT '模拟初始资金',
  `stop_loss_pct` decimal(5,2) DEFAULT NULL COMMENT '止损百分比',
  `take_profit_pct` decimal(5,2) DEFAULT NULL COMMENT '止盈百分比',
  `fee_rate` decimal(10,6) DEFAULT NULL COMMENT '手续费率（如 0.001000 = 0.1%）',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '删除标记',
  `leverage` int NOT NULL DEFAULT '1' COMMENT '1-125',
  `margin_type` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'ISOLATED' COMMENT 'ISOLATED/CROSS',
  `atr_stop_multiplier` decimal(10,4) DEFAULT NULL COMMENT 'ATR 2.0(stop_loss_pct)',
  `atr_take_profit_multiplier` decimal(10,4) DEFAULT NULL COMMENT 'ATR 3.0(take_profit_pct)',
  `initial_stop_multiplier` decimal(10,4) DEFAULT NULL COMMENT 'NATRATR',
  `breakeven_activation_multiplier` decimal(10,4) DEFAULT NULL COMMENT 'NATR',
  `trailing_activation_multiplier` decimal(10,4) DEFAULT NULL COMMENT 'NATR',
  `trailing_distance_multiplier` decimal(10,4) DEFAULT NULL COMMENT 'NATR',
  `atr_interval` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ATRK',
  `exit_indicator_configs` text COLLATE utf8mb4_unicode_ci COMMENT 'JSONindicatorConfigs',
  `max_holding_bars` int DEFAULT NULL COMMENT 'KNULL=',
  `trailing_drop_pct` decimal(10,4) DEFAULT NULL COMMENT ' 5.0 = 5%()/()',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`,`deleted`),
  KEY `idx_exchange_symbol` (`exchange`,`symbol`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='策略配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `stg_strategy`
--

LOCK TABLES `stg_strategy` WRITE;
/*!40000 ALTER TABLE `stg_strategy` DISABLE KEYS */;
INSERT INTO `stg_strategy` VALUES (2020583259263492068,'TRXUSDT_del_2020583259263492068',NULL,'binance','TRXUSDT','M15','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"M15\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10}]',0,1,NULL,'AUTO','PAPER',2023534755928092674,'FIXED',1.0000000000,1.00,10000.0000000000,NULL,NULL,NULL,1,NULL,1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2020583259263492078,'BNBUSDT',NULL,'binance','BNBUSDT','M15','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"M15\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10}]',1,0,70,'AUTO','PAPER',2023534755928092674,'FIXED',1.0000000000,1.00,10000.0000000000,NULL,NULL,NULL,1,NULL,1,'2026-03-19 09:55:19',0,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2020583259263492088,'BTCUSDT_del_2020583259263492088',NULL,'binance','BTCUSDT','M15','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"M15\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10}]',0,1,NULL,'AUTO','PAPER',2023534755928092674,'FIXED',1.0000000000,1.00,10000.0000000000,NULL,NULL,NULL,1,NULL,1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2020583259263492098,'ETHUSDT_del_2020583259263492098',NULL,'binance','ETHUSDT','M5','[{\"hardFilter\":false,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":12},\"weight\":15},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":24,\"volMultiplier\":1.5},\"weight\":25},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":30},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":30}]',0,0,60,'AUTO','PAPER',2023534755928092674,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,NULL,1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2020585033785450497,'cs_del_2020585033785450497',NULL,'binance','ETHUSDT','H4','[{\"indicatorType\":\"EMA\",\"params\":{\"period\":2},\"weight\":30}]',0,0,NULL,'AUTO','PAPER',NULL,'FIXED',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,NULL,NULL,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2028203954880569346,'ETH-1m2_del_2028203954880569346','频率调整为1分钟','binance','ETHUSDT','M5','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":24,\"volMultiplier\":2},\"weight\":20},{\"indicatorType\":\"OBV\",\"interval\":\"M15\",\"params\":{\"signalPeriod\":24},\"weight\":28},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10},{\"indicatorType\":\"KDJ\",\"interval\":\"M5\",\"params\":{\"rsvPeriod\":9,\"kPeriod\":3,\"dPeriod\":3},\"weight\":2},{\"indicatorType\":\"MACD\",\"interval\":\"H1\",\"params\":{\"fast\":24,\"slow\":48,\"signal\":12},\"weight\":10}]',0,1,NULL,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-01 20:21:20',1,'2026-04-04 22:57:57',1,1,'ISOLATED',1.5000,50.0000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2028243338619183105,'ETH成交量策略_del_2028243338619183105',NULL,'binance','ETHUSDT','M5','[{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":24,\"volMultiplier\":2},\"weight\":18},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"M5\",\"params\":{\"period\":24,\"multiplier\":2},\"weight\":9},{\"indicatorType\":\"OBV\",\"interval\":\"M5\",\"params\":{\"signalPeriod\":30},\"weight\":9},{\"indicatorType\":\"MA\",\"interval\":\"M5\",\"params\":{\"period\":28},\"weight\":12},{\"indicatorType\":\"ADX\",\"interval\":\"M5\",\"params\":{\"period\":30,\"trendThreshold\":40},\"weight\":12},{\"indicatorType\":\"MACD\",\"interval\":\"M5\",\"params\":{\"fast\":24,\"slow\":52,\"signal\":18},\"weight\":20}]',0,0,NULL,NULL,'PAPER',NULL,'FIXED',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-01 22:57:50',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2028782751447240706,'123_del_2028782751447240706',NULL,'binance','ETHUSDT','M15','[{\"indicatorType\":\"MA\",\"params\":{\"period\":20},\"weight\":50}]',0,0,NULL,NULL,'PAPER',NULL,'FIXED',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-03 10:41:16',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2028804349050892290,'趋势+放量确认-过滤假突破_del_2028804349050892290',NULL,'binance','ETHUSDT','M15','[{\"indicatorType\":\"SUPERTREND\",\"interval\":\"M15\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":60},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M30\",\"params\":{\"period\":50,\"volMultiplier\":2},\"weight\":40}]',0,0,NULL,NULL,'PAPER',NULL,'FIXED',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-03 12:07:05',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2029828410178686977,'大势趋势跟踪_del_2029828410178686977','适合 BTC/ETH 主流币，大波动行情','binance','ETHUSDT','M15','[{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":3},\"weight\":40},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M15\",\"params\":{\"period\":14,\"trendThreshold\":30},\"weight\":30}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,0.001000,1,'2026-03-06 07:56:20',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2029829049851990018,'日内波段回归_del_2029829049851990018',NULL,'binance','ETHUSDT','M15','[{\"indicatorType\":\"EMA\",\"interval\":\"H1\",\"params\":{\"period\":60},\"weight\":20},{\"indicatorType\":\"BOLL\",\"interval\":\"M15\",\"params\":{\"period\":20,\"multiplier\":2},\"weight\":40},{\"indicatorType\":\"STOCH_RSI\",\"interval\":\"M15\",\"params\":{\"rsiPeriod\":14,\"stochPeriod\":14,\"kSmooth\":3,\"dSmooth\":3},\"weight\":40}]',0,1,NULL,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,1.50,4.00,NULL,1,'2026-03-06 07:58:53',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030189428197244929,'三维共振_del_2030189428197244929','长线策略','binance','ETHUSDT','M30','[{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":14,\"multiplier\":3},\"weight\":25},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M30\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"weight\":20},{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":14},\"weight\":20},{\"indicatorType\":\"MACD\",\"interval\":\"M30\",\"params\":{\"fast\":12,\"slow\":26,\"signal\":9},\"weight\":15},{\"indicatorType\":\"ADX\",\"interval\":\"H1\",\"params\":{\"period\":14,\"trendThreshold\":25},\"weight\":20}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-07 07:50:53',1,'2026-04-04 22:57:57',1,1,'ISOLATED',1.5000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030190753945436162,'捕食者_del_2030190753945436162','短线','binance','ETHUSDT','M1','[{\"indicatorType\":\"RSI\",\"interval\":\"M5\",\"params\":{\"period\":10},\"weight\":35},{\"indicatorType\":\"KDJ\",\"interval\":\"M1\",\"params\":{\"rsvPeriod\":9,\"kPeriod\":3,\"dPeriod\":3},\"weight\":25},{\"indicatorType\":\"BOLL\",\"interval\":\"M5\",\"params\":{\"period\":20,\"multiplier\":2},\"weight\":20},{\"indicatorType\":\"WR\",\"interval\":\"M1\",\"params\":{\"period\":14},\"weight\":20}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,0.50,4.00,NULL,1,'2026-03-07 07:56:10',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030271291725160449,'日内_del_2030271291725160449',NULL,'binance','ETHUSDT','M5','[{\"indicatorType\":\"EMA\",\"interval\":\"H1\",\"params\":{\"period\":200},\"weight\":50},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M15\",\"params\":{\"period\":20,\"volMultiplier\":2},\"weight\":20},{\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14},\"weight\":15},{\"indicatorType\":\"ADX\",\"interval\":\"H1\",\"params\":{\"period\":14,\"trendThreshold\":25},\"weight\":15}]',0,1,NULL,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-07 13:16:11',1,'2026-04-04 22:57:57',1,1,'ISOLATED',2.0000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030278362335977473,'ETHUSDT (副本)_del_2030278362335977473',NULL,'binance','ETHUSDT','M15','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"M15\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10}]',0,1,60,'AUTO','LIVE',2025975215706738690,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-07 13:44:17',1,'2026-04-04 22:57:57',1,1,'ISOLATED',2.0000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030283351494107138,'捕食者 (副本)_del_2030283351494107138','短线','binance','ETHUSDT','M1','[{\"indicatorType\":\"RSI\",\"interval\":\"M5\",\"params\":{\"period\":10},\"weight\":35},{\"indicatorType\":\"KDJ\",\"interval\":\"M1\",\"params\":{\"rsvPeriod\":9,\"kPeriod\":3,\"dPeriod\":3},\"weight\":25},{\"indicatorType\":\"BOLL\",\"interval\":\"M5\",\"params\":{\"period\":20,\"multiplier\":2},\"weight\":20},{\"indicatorType\":\"WR\",\"interval\":\"M1\",\"params\":{\"period\":14},\"weight\":20}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,0.50,NULL,NULL,1,'2026-03-07 14:04:06',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030284776064294913,'ETHUSDT333_del_2030284776064294913',NULL,'binance','ETHUSDT','M15','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M15\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"M15\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10}]',0,1,60,'AUTO','LIVE',2025975215706738690,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-07 14:09:46',1,'2026-04-04 22:57:57',1,1,'ISOLATED',1.5000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030285289254166529,'232_del_2030285289254166529','23','binance','ETHUSDT','M1','[{\"indicatorType\":\"MA\",\"interval\":\"M1\",\"params\":{\"period\":14},\"penaltyWeight\":0,\"weight\":50}]',0,0,90,NULL,'PAPER',NULL,'FIXED',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-07 14:11:48',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030307659213926401,'ETHUSDTzzzz_del_2030307659213926401',NULL,'binance','ETHUSDT','M5','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H4\",\"params\":{\"period\":24,\"volMultiplier\":2},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"H1\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M5\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"M30\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"M30\",\"params\":{\"period\":24},\"weight\":10}]',0,1,NULL,'AUTO','LIVE',2025975215706738690,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-07 15:40:42',1,'2026-04-04 22:57:57',1,1,'ISOLATED',2.0000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030309402563801089,'ETHUSDT-长线_del_2030309402563801089',NULL,'binance','ETHUSDT','M5','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H4\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"H1\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10}]',0,1,70,'AUTO','LIVE',2025975215706738690,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-07 15:47:38',1,'2026-04-04 22:57:57',1,1,'ISOLATED',2.0000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030310386908229634,'ETHUSDT-长线 (副本)_del_2030310386908229634',NULL,'binance','ETHUSDT','M5','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M30\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"BOLL\",\"interval\":\"M15\",\"params\":{\"period\":20,\"multiplier\":2},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"M30\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"MACD\",\"interval\":\"M5\",\"params\":{\"fast\":24,\"slow\":56,\"signal\":12},\"weight\":10}]',0,1,NULL,'AUTO','LIVE',2025975215706738690,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-07 15:51:32',1,'2026-04-04 22:57:57',1,1,'ISOLATED',2.0000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030311194240446465,'ETHUSDT-长线-2_del_2030311194240446465',NULL,'binance','ETHUSDT','M5','[{\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"weight\":20},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H4\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":40},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":30},{\"indicatorType\":\"BOLL\",\"interval\":\"M5\",\"params\":{\"period\":12,\"multiplier\":2},\"penaltyWeight\":0,\"weight\":10}]',0,1,NULL,'AUTO','LIVE',2025975215706738690,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-07 15:54:45',1,'2026-04-04 22:57:57',1,1,'ISOLATED',2.0000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030312596232695809,'ETHUSDT-长线-x_del_2030312596232695809',NULL,'binance','ETHUSDT','M5','[{\"indicatorType\":\"RSI\",\"interval\":\"M30\",\"params\":{\"period\":36},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H4\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"H1\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":10},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10},{\"indicatorType\":\"WR\",\"interval\":\"M1\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"weight\":10}]',0,1,70,'AUTO','LIVE',2025975215706738690,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-07 16:00:19',1,'2026-04-04 22:57:57',1,1,'ISOLATED',2.0000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2030333139325513730,'短期合约_del_2030333139325513730',NULL,'binance','ETHUSDT','M1','[{\"indicatorType\":\"MA\",\"interval\":\"H1\",\"params\":{\"period\":60},\"penaltyWeight\":0,\"weight\":30},{\"indicatorType\":\"BOLL\",\"interval\":\"M15\",\"params\":{\"period\":20,\"multiplier\":2.3},\"penaltyWeight\":0,\"weight\":40},{\"indicatorType\":\"VWAP\",\"interval\":\"M15\",\"params\":{\"deviationPct\":0.65},\"penaltyWeight\":0,\"weight\":30}]',0,1,90,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-07 17:21:57',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,2.5000,1.2000,2.0000,1.5000,'M15',NULL,NULL,NULL),(2030950374368866305,'ETHUSDT (副本)xxx_del_2030950374368866305',NULL,'binance','ETHUSDT','M15','[{\"indicatorType\":\"RSI\",\"interval\":\"M5\",\"params\":{\"period\":24},\"weight\":10},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"M15\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":30,\"trendThreshold\":40},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":24,\"multiplier\":3},\"weight\":20}]',0,1,60,'AUTO','LIVE',2025975215706738690,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-09 10:14:37',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,2.0000,1.8000,2.5000,2.0000,'M5',NULL,NULL,NULL),(2032086552379838465,'ETHUSDT-v2_del_2032086552379838465',NULL,'binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi36\",\"op\":\"LT\",\"threshold\":40.0},{\"applyTo\":\"SELL\",\"field\":\"rsi36\",\"op\":\"GT\",\"threshold\":60.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":36}},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":48,\"volMultiplier\":1.5},\"weight\":30},{\"indicatorType\":\"OBV\",\"interval\":\"M15\",\"params\":{\"signalPeriod\":24},\"weight\":30},{\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":24,\"trendThreshold\":35},\"weight\":10},{\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":30,\"multiplier\":3},\"weight\":20},{\"indicatorType\":\"ATR\",\"interval\":\"H1\",\"params\":{\"period\":24},\"weight\":10}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',3.0000000000,1.00,7000.0000000000,NULL,NULL,NULL,1,'2026-03-12 13:29:23',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,4.0000,3.0000,4.0000,2.0000,'M15',NULL,NULL,NULL),(2032116792300851202,'新策略测试',NULL,'binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":65.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":3}},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":40},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M15\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"buyConditions\":[],\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30}]',1,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-12 15:29:33',1,'2026-03-27 15:05:52',0,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2032123519192715265,'短线_del_2032123519192715265',NULL,'binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"SELL\",\"field\":\"rsi14\",\"op\":\"GT\",\"threshold\":55.0},{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":40.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"indicatorType\":\"VWAP\",\"interval\":\"M5\",\"params\":{\"deviationPct\":0.05},\"penaltyWeight\":0,\"weight\":40},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":2},\"penaltyWeight\":0,\"weight\":30},{\"indicatorType\":\"KDJ\",\"interval\":\"M15\",\"params\":{\"rsvPeriod\":9,\"kPeriod\":3,\"dPeriod\":3},\"penaltyWeight\":0,\"weight\":30}]',0,1,60,'AUTO','PAPER',NULL,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-12 15:56:17',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,4.0000,2.0000,3.5000,2.0000,'M5',NULL,NULL,NULL),(2032422818594082817,'快进快出合约策略_del_2032422818594082817',NULL,'binance','ETHUSDT','M1','[{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"STOCH_RSI\",\"interval\":\"M1\",\"params\":{\"rsiPeriod\":14,\"stochPeriod\":14,\"kSmooth\":3,\"dSmooth\":3}},{\"filterConditions\":[{\"applyTo\":\"SELL\",\"field\":\"wr14\",\"op\":\"GT\",\"threshold\":-15.0},{\"applyTo\":\"BUY\",\"field\":\"wr14\",\"op\":\"LT\",\"threshold\":-85.0}],\"hardFilter\":true,\"indicatorType\":\"WR\",\"interval\":\"M1\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"deviation\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"deviation\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"VWAP\",\"interval\":\"M1\",\"params\":{\"deviationPct\":0.2}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"M5\",\"params\":{\"period\":10,\"multiplier\":3}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":60.0},{\"applyTo\":\"SELL\",\"field\":\"rsi14\",\"op\":\"GT\",\"threshold\":75.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M1\",\"params\":{\"period\":14}},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"weight\":100}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-13 11:45:35',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2032560415358242817,'非对称硬性过滤策略_del_2032560415358242817',NULL,'binance','BTCUSDT','M5','[{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":2.5}},{\"indicatorType\":\"MACD\",\"interval\":\"M15\",\"params\":{\"fast\":12,\"slow\":26,\"signal\":9},\"penaltyWeight\":0,\"weight\":20},{\"indicatorType\":\"VWAP\",\"interval\":\"M15\",\"params\":{\"deviationPct\":0.1},\"penaltyWeight\":0,\"weight\":40},{\"hardFilter\":false,\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":10,\"volMultiplier\":2},\"weight\":40}]',0,1,60,'AUTO','PAPER',2025975215706738690,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-13 20:52:21',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,4.0000,2.0000,3.5000,4.0000,'M15',NULL,NULL,NULL),(2032569631519199234,'新策略测试-ETH','ETH专属策略','binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":75.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":2}},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"weight\":30},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"weight\":40},{\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"weight\":30},{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"ADX\",\"interval\":\"H1\",\"params\":{\"period\":12,\"trendThreshold\":25}}]',1,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-13 21:28:58',1,'2026-03-26 13:17:34',0,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,'M15',NULL,NULL,NULL),(2032578708248907777,'新策略测试-BTC_del_2032578708248907777',NULL,'binance','BTCUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":75.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":2}},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"weight\":30},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"weight\":40},{\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"weight\":30},{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"ADX\",\"interval\":\"H1\",\"params\":{\"period\":12,\"trendThreshold\":25}}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-13 22:05:02',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,4.0000,3.0000,NULL,NULL,'M30',NULL,NULL,NULL),(2032579359183917057,'新策略测试-BNB_del_2032579359183917057',NULL,'binance','BTCUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":75.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":2}},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"weight\":30},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"weight\":40},{\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"weight\":30},{\"filterConditions\":[{\"field\":\"adx\",\"op\":\"GTE\",\"threshold\":25.0}],\"hardFilter\":true,\"indicatorType\":\"ADX\",\"interval\":\"H1\",\"params\":{\"period\":12,\"trendThreshold\":25}}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-13 22:07:37',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,'M15',NULL,NULL,NULL),(2032714138608914433,'新策略测试-ETH (副本)_del_2032714138608914433','ETH专属策略','binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":75.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":2}},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"weight\":30},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"weight\":40},{\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"weight\":20},{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"ADX\",\"interval\":\"H1\",\"params\":{\"period\":12,\"trendThreshold\":25}},{\"indicatorType\":\"VWAP\",\"interval\":\"M30\",\"params\":{\"deviationPct\":0.2},\"penaltyWeight\":0,\"weight\":10}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-14 07:03:11',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,6.0000,4.0000,3.0000,4.0000,'M30',NULL,NULL,NULL),(2032817243124072450,'新架构测试_del_2032817243124072450',NULL,'binance','ETHUSDT','M5','[{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"weight\":40},{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"M15\",\"params\":{\"period\":10,\"multiplier\":1.5}},{\"indicatorType\":\"KDJ\",\"interval\":\"M5\",\"params\":{\"rsvPeriod\":9,\"kPeriod\":3,\"dPeriod\":3},\"penaltyWeight\":0,\"weight\":30},{\"indicatorType\":\"STOCH_RSI\",\"interval\":\"M15\",\"params\":{\"rsiPeriod\":14,\"stochPeriod\":14,\"kSmooth\":3,\"dSmooth\":3},\"penaltyWeight\":0,\"weight\":30}]',0,0,60,NULL,'PAPER',NULL,'FIXED',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-14 13:52:53',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,'[{\"indicatorType\":\"EMA\",\"interval\":\"M15\",\"params\":{\"period\":20},\"penaltyWeight\":0,\"weight\":50},{\"indicatorType\":\"BOLL\",\"interval\":\"M15\",\"params\":{\"period\":20,\"multiplier\":2},\"penaltyWeight\":0,\"weight\":50}]',NULL,NULL),(2036068922296553473,'新策略测试 (副本)_del_2036068922296553473',NULL,'binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":65.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":3}},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"weight\":40},{\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M15\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"weight\":30},{\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"weight\":30}]',0,1,50,'AUTO','LIVE',2025975215706738690,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-23 13:13:54',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2037130527759327233,'新策略测试-ETH (副本)xxxx_del_2037130527759327233','ETH专属策略','binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":75.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":2}},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":40},{\"buyConditions\":[],\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"ADX\",\"interval\":\"H1\",\"params\":{\"period\":12,\"trendThreshold\":25}}]',0,1,60,'AUTO','PAPER',2037137962498727938,'PERCENT',NULL,1.00,10000.0000000000,NULL,NULL,NULL,1,'2026-03-26 11:32:21',1,'2026-04-04 22:57:57',1,1,'ISOLATED',NULL,NULL,NULL,NULL,NULL,NULL,'M15',NULL,NULL,NULL),(2037193720665919489,'稳健合约',NULL,'binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":65.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":3}},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":40},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M15\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"buyConditions\":[],\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30}]',0,1,60,'AUTO','PAPER',2037137962498727938,'PERCENT',NULL,1.00,10000.0000000000,3.00,NULL,NULL,1,'2026-03-26 15:43:27',1,'2026-03-26 17:31:00',0,5,'CROSS',NULL,NULL,NULL,NULL,NULL,NULL,NULL,'[{\"buyConditions\":[],\"indicatorType\":\"SUPERTREND\",\"interval\":\"M30\",\"params\":{\"period\":10,\"multiplier\":3},\"penaltyWeight\":0,\"sellConditions\":[{\"field\":\"prevTrend\",\"op\":\"GT\",\"threshold\":0.0},{\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"weight\":50}]',NULL,NULL),(2037220876020281345,'ETH-高收益-高回撤',NULL,'binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":65.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":3}},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":40},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M15\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"buyConditions\":[],\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":24},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"adx\",\"op\":\"GT\",\"threshold\":20.0}],\"hardFilter\":true,\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":14,\"trendThreshold\":25}}]',0,1,60,'AUTO','PAPER',2037137962498727938,'PERCENT',NULL,1.00,10000.0000000000,5.00,NULL,NULL,1,'2026-03-26 17:31:21',1,'2026-03-26 19:04:22',0,3,'CROSS',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),(2037222651905372162,'ETH-SWAP-3','ETH专属策略','binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":75.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":2}},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":40},{\"buyConditions\":[],\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":20},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":14,\"trendThreshold\":25}}]',1,1,60,'AUTO','LIVE',2023534755928092679,'PERCENT',NULL,0.90,10000.0000000000,15.00,NULL,NULL,1,'2026-03-26 17:38:25',1,'2026-04-05 12:59:55',0,3,'CROSS',NULL,NULL,NULL,NULL,NULL,NULL,'H1',NULL,NULL,NULL),(2037465561053696002,'ETH-SPOT','ETH专属策略','binance','ETHUSDT','M5','[{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"rsi14\",\"op\":\"LT\",\"threshold\":75.0}],\"hardFilter\":true,\"indicatorType\":\"RSI\",\"interval\":\"M15\",\"params\":{\"period\":14}},{\"filterConditions\":[{\"applyTo\":\"BUY\",\"field\":\"trend\",\"op\":\"GT\",\"threshold\":0.0},{\"applyTo\":\"SELL\",\"field\":\"trend\",\"op\":\"LT\",\"threshold\":0.0}],\"hardFilter\":true,\"indicatorType\":\"SUPERTREND\",\"interval\":\"H1\",\"params\":{\"period\":10,\"multiplier\":2}},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"H1\",\"params\":{\"period\":20,\"volMultiplier\":1.8},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"buyConditions\":[],\"indicatorType\":\"VOL_CONFIRM\",\"interval\":\"M5\",\"params\":{\"period\":20,\"volMultiplier\":1.5},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":40},{\"buyConditions\":[],\"indicatorType\":\"ATR\",\"interval\":\"M15\",\"params\":{\"period\":20},\"penaltyWeight\":0,\"sellConditions\":[],\"weight\":30},{\"filterConditions\":[],\"hardFilter\":true,\"indicatorType\":\"ADX\",\"interval\":\"M30\",\"params\":{\"period\":14,\"trendThreshold\":25}}]',0,1,60,'AUTO','PAPER',2023534755928092674,'PERCENT',NULL,1.00,10000.0000000000,15.00,NULL,NULL,1,'2026-03-27 09:43:39',1,'2026-03-27 12:55:55',0,1,'CROSS',NULL,NULL,NULL,NULL,NULL,NULL,'H1',NULL,NULL,NULL);
/*!40000 ALTER TABLE `stg_strategy` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_menu`
--

DROP TABLE IF EXISTS `sys_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `id` bigint NOT NULL COMMENT '菜单ID',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '菜单名称',
  `i18n_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '多语言key',
  `path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '路由路径',
  `component` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '组件路径',
  `icon` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '图标',
  `type` tinyint NOT NULL COMMENT '菜单类型 0-目录 1-菜单 2-按钮',
  `permission` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限标识',
  `sort` int DEFAULT '0' COMMENT '排序',
  `status` tinyint DEFAULT '1' COMMENT '状态 0-禁用 1-启用',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '删除标记 0-正常 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_type` (`type`),
  KEY `idx_status` (`status`),
  KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_menu`
--

LOCK TABLES `sys_menu` WRITE;
/*!40000 ALTER TABLE `sys_menu` DISABLE KEYS */;
INSERT INTO `sys_menu` VALUES (1,0,'','text.system.title',NULL,NULL,'AppstoreOutlined',0,NULL,1,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-03 18:34:51',0),(2,0,'','text.quote.title',NULL,NULL,'StockOutlined',0,NULL,2,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-03 18:34:51',0),(3,0,'','text.strategy.title',NULL,NULL,'FundOutlined',0,NULL,3,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-03 18:34:51',0),(4,0,'','text.trading.title',NULL,NULL,'SwapOutlined',0,NULL,4,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-03 18:34:51',0),(5,0,'','text.chain.title',NULL,NULL,'NodeExpandOutlined',0,NULL,5,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-03 18:34:51',0),(11,1,'','text.system.user','/user','UserManagement','UserOutlined',1,'system:user',1,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(12,1,'','text.system.menu','/menu','MenuManagement','MenuOutlined',1,'system:menu',2,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(13,1,'','text.system.role','/role','RoleManagement','SafetyOutlined',1,'system:role',3,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(21,2,'','text.quote.sourceTitle','/quote/source','QuoteSource','ApiOutlined',1,'quote:source',1,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(22,2,'K','text.quote.klineTitle','/quote/kline','KlineManagement','LineChartOutlined',1,'quote:kline',2,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(31,3,'','text.strategy.configTitle','/strategy/config','StrategyManagement','SettingOutlined',1,'strategy:config',1,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(32,3,'','text.strategy.signalTitle','/strategy/signals','SignalManagement','ThunderboltOutlined',1,'strategy:signal',2,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(41,4,'','text.trading.accountTitle','/trading/accounts','AccountManagement','BankOutlined',1,'trade:account',1,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(42,4,'','text.trading.orderTitle','/trading/orders','OrderManagement','OrderedListOutlined',1,'trade:order',2,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(43,4,'','text.trading.positionTitle','/trading/positions','PositionManagement','DashboardOutlined',1,'trade:position',3,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(44,4,'','text.trading.pnlAnalysisTitle','/trading/pnl','PnlAnalysis','PieChartOutlined',1,'trade:pnl',4,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(45,4,'','text.trading.symbolTitle','/trading/symbols','SymbolManagement','DatabaseOutlined',1,'trade:symbol',5,1,NULL,'2026-04-04 23:15:44',NULL,'2026-04-04 23:16:00',0),(51,5,'','text.chain.tokenListTitle','/chain/tokens','TokenManagement','FundOutlined',1,'chain:token',1,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(52,5,'','text.chain.alertRuleTitle','/chain/alerts','AlertManagement','BellOutlined',1,'chain:alert',2,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-05 16:08:20',0),(53,5,'','text.chain.sourceConfigTitle','/chain/source-config','SourceConfig','SettingOutlined',1,NULL,3,1,NULL,'2026-03-17 03:26:36',NULL,'2026-03-17 03:26:36',0),(60,0,'','text.guide.title','/guide/strategy','StrategyGuide','BookOutlined',1,NULL,10,1,NULL,'2026-03-03 18:34:51',NULL,'2026-03-03 18:34:51',0);
/*!40000 ALTER TABLE `sys_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_notify_config`
--

DROP TABLE IF EXISTS `sys_notify_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_notify_config` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT 'ID',
  `channel` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'TELEGRAM' COMMENT ' TELEGRAM',
  `chat_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Telegram Chat ID',
  `enabled` tinyint DEFAULT '1' COMMENT ' 0- 1-',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0' COMMENT ' 0- 1-',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_channel` (`user_id`,`channel`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_notify_config`
--

LOCK TABLES `sys_notify_config` WRITE;
/*!40000 ALTER TABLE `sys_notify_config` DISABLE KEYS */;
INSERT INTO `sys_notify_config` VALUES (2033861455550533633,1,'TELEGRAM','7837034041',1,1,'2026-03-17 11:02:13',1,'2026-03-17 11:02:13',0);
/*!40000 ALTER TABLE `sys_notify_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role`
--

DROP TABLE IF EXISTS `sys_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `id` bigint NOT NULL COMMENT '角色ID',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色名称',
  `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色编码',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `status` tinyint DEFAULT '1' COMMENT '状态 0-禁用 1-启用',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '删除标记 0-正常 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_status` (`status`),
  KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role`
--

LOCK TABLES `sys_role` WRITE;
/*!40000 ALTER TABLE `sys_role` DISABLE KEYS */;
INSERT INTO `sys_role` VALUES (2028783498977071105,'管理员','administrator',NULL,1,1,'2026-03-03 10:44:14',1,'2026-03-03 10:44:14',0),(2028785388599103490,'测试角色','vertest','vertest',1,1,'2026-03-03 10:51:44',1,'2026-03-03 10:51:44',0);
/*!40000 ALTER TABLE `sys_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_menu`
--

DROP TABLE IF EXISTS `sys_role_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `id` bigint NOT NULL COMMENT '关联ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '删除标记 0-正常 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_id`),
  KEY `idx_role_id` (`role_id`),
  KEY `idx_menu_id` (`menu_id`),
  KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色菜单关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_menu`
--

LOCK TABLES `sys_role_menu` WRITE;
/*!40000 ALTER TABLE `sys_role_menu` DISABLE KEYS */;
INSERT INTO `sys_role_menu` VALUES (2029915603635257345,2028785388599103490,2,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603681394689,2028785388599103490,3,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603731726338,2028785388599103490,4,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603752697857,2028785388599103490,22,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603773669378,2028785388599103490,31,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603803029506,2028785388599103490,32,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603844972546,2028785388599103490,41,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603886915585,2028785388599103490,42,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603920470017,2028785388599103490,43,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603945635841,2028785388599103490,44,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2029915603974995969,2028785388599103490,60,1,'2026-03-06 13:42:49',1,'2026-03-06 13:42:49',0),(2040449606641471489,2028783498977071105,1,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606691803137,2028783498977071105,11,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606733746178,2028783498977071105,12,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606767300609,2028783498977071105,13,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606788272129,2028783498977071105,2,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606813437953,2028783498977071105,21,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606830215169,2028783498977071105,22,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606846992385,2028783498977071105,3,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606859575298,2028783498977071105,31,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606859575299,2028783498977071105,32,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606893129729,2028783498977071105,4,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606905712642,2028783498977071105,41,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606943461378,2028783498977071105,42,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606968627202,2028783498977071105,43,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449606993793026,2028783498977071105,44,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449607018958850,2028783498977071105,5,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449607031541762,2028783498977071105,51,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449607052513281,2028783498977071105,52,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449607069290497,2028783498977071105,60,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0),(2040449607077679106,2028783498977071105,45,1,'2026-04-04 15:21:11',1,'2026-04-04 15:21:11',0);
/*!40000 ALTER TABLE `sys_role_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user`
--

DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL COMMENT '用户ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '昵称',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像',
  `gender` tinyint DEFAULT '0' COMMENT '性别 0-未知 1-男 2-女',
  `account_type` tinyint DEFAULT '0' COMMENT '账户类型 0-系统账户 1-代理账户',
  `status` tinyint DEFAULT '1' COMMENT '状态 0-禁用 1-正常',
  `locked` tinyint(1) DEFAULT '0' COMMENT ' 0- 1-',
  `login_fail_count` int DEFAULT '0',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '删除标记 0-正常 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_phone` (`phone`),
  KEY `idx_account_type` (`account_type`),
  KEY `idx_status` (`status`),
  KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user`
--

LOCK TABLES `sys_user` WRITE;
/*!40000 ALTER TABLE `sys_user` DISABLE KEYS */;
INSERT INTO `sys_user` VALUES (1,'admin','$2a$10$aLYrdXnaanaJN6EEvBPOMuknsAnRwLY/kYxDyeHW2pIx1AqCngX16','管理员','13800138000','admin@vertex.com',NULL,1,0,1,0,0,NULL,'2026-01-16 04:27:15',1,'2026-04-06 06:59:23',0),(2011903101283389441,'test','admin123',NULL,'13588888888',NULL,NULL,0,0,1,0,0,NULL,NULL,NULL,NULL,0),(2028785276657324033,'vertest','$2a$10$Hlz7Ll4ldofKZ/723aeD2O2bNOZml9.UbLvY42lWv9oeGrCyhn97K','vertest','13588888888',NULL,NULL,0,1,1,0,0,1,'2026-03-03 10:51:18',1,'2026-04-04 15:52:28',0),(2029187669261778945,'theo','$2a$10$9fnRdwLMQMDP6p.xesl9sOCaY0MSW3dtE04vqrAWjYLAg9IhKx4wi','Theo','13588888888',NULL,NULL,0,1,1,0,0,1,'2026-03-04 13:30:15',1,'2026-03-06 13:50:03',0);
/*!40000 ALTER TABLE `sys_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user_role`
--

DROP TABLE IF EXISTS `sys_user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `id` bigint NOT NULL COMMENT '关联ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '删除标记 0-正常 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_role_id` (`role_id`),
  KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user_role`
--

LOCK TABLES `sys_user_role` WRITE;
/*!40000 ALTER TABLE `sys_user_role` DISABLE KEYS */;
INSERT INTO `sys_user_role` VALUES (2028783616279171073,1,2028783498977071105,1,'2026-03-03 10:44:42',1,'2026-03-03 10:44:42',0),(2028785561257627649,2028785276657324033,2028785388599103490,1,'2026-03-03 10:52:25',1,'2026-03-03 10:52:25',0),(2029187759426732033,2029187669261778945,2028785388599103490,1,'2026-03-04 13:30:37',1,'2026-03-04 13:30:37',0);
/*!40000 ALTER TABLE `sys_user_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `trd_exchange_account`
--

DROP TABLE IF EXISTS `trd_exchange_account`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `trd_exchange_account` (
  `id` bigint NOT NULL COMMENT '主键',
  `name` varchar(100) NOT NULL COMMENT '账户名称',
  `exchange` varchar(50) NOT NULL DEFAULT 'binance' COMMENT '交易所',
  `api_key` text NOT NULL COMMENT 'API Key (AES-GCM加密)',
  `api_secret` text NOT NULL COMMENT 'API Secret (AES-GCM加密)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0-禁用 1-正常',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` int NOT NULL DEFAULT '0',
  `market_type` varchar(10) NOT NULL DEFAULT 'SPOT' COMMENT 'SPOT/USDM/COINM',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交易所账户';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `trd_exchange_account`
--

LOCK TABLES `trd_exchange_account` WRITE;
/*!40000 ALTER TABLE `trd_exchange_account` DISABLE KEYS */;
INSERT INTO `trd_exchange_account` VALUES (2023534755928092674,'模拟盘','binance','rdOG+9w53bEB9BUZzOsFHRQXxoVTUV/MSOM8hjTHDxxJW6OtJhqWSUASUsWsLA==','a+1oGSDDAzIdIMncsLaBXj4GWLWXBZdFgpZ2n88vsi2i2J75xCX/KCnkog==',1,NULL,NULL,1,'2026-03-18 12:17:06',0,'SPOT'),(2023534755928092679,'合约测试','binance','q/mlIpHP1u1IXbtUPsCGzLvE/w4nKUpj7mlgZcT96xrKRIORaYTcC0yCmGENdTfbeXYlpE5LGT0F6OJwGUD/mj/AE1mgnNrdcw9861cQwqbkRxoLMxr7b8FAwb4=','dKiuQsaSiou9jRIL7PFRaX9wBIaTMxdRLGn8xJr+viXTb/Mg0ZowoUKyPC5bG/fzYfiN3Wfz4aJ3ixuGpW+zYHS/SqfN/0O/dX3ajPuj4vFuIk98N2uO5CNolb8=',1,1,NULL,1,'2026-03-26 16:09:29',0,'USDM'),(2025975215706738690,'测试','binance','q/mlIpHP1u1IXbtUPsCGzLvE/w4nKUpj7mlgZcT96xrKRIORaYTcC0yCmGENdTfbeXYlpE5LGT0F6OJwGUD/mj/AE1mgnNrdcw9861cQwqbkRxoLMxr7b8FAwb4=','dKiuQsaSiou9jRIL7PFRaX9wBIaTMxdRLGn8xJr+viXTb/Mg0ZowoUKyPC5bG/fzYfiN3Wfz4aJ3ixuGpW+zYHS/SqfN/0O/dX3ajPuj4vFuIk98N2uO5CNolb8=',1,NULL,'2026-02-23 16:45:07',NULL,'2026-02-23 16:45:07',0,'SPOT'),(2037137962498727938,'合约模拟测试','binance','SYTRsWAgYywGtzjfjw+UobNp0yqihiCJrQX4dJWrIg==','8wGCty+kKA0ZJ7XOcAukYCyTedNKL5EKFdg5LO6R9A==',1,1,'2026-03-26 12:01:53',1,'2026-03-26 12:01:53',0,'USDM');
/*!40000 ALTER TABLE `trd_exchange_account` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `trd_exchange_symbol`
--

DROP TABLE IF EXISTS `trd_exchange_symbol`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `trd_exchange_symbol` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(30) NOT NULL,
  `exchange` varchar(20) NOT NULL COMMENT ' binance',
  `market_type` varchar(10) NOT NULL COMMENT 'SPOT/USDM/COINM',
  `exchange_symbol` varchar(40) NOT NULL,
  `status` varchar(10) NOT NULL DEFAULT 'TRADING' COMMENT 'TRADING/BREAK ',
  `min_notional` decimal(20,8) DEFAULT NULL,
  `lot_size` decimal(20,8) DEFAULT NULL COMMENT 'stepSize',
  `tick_size` decimal(20,8) DEFAULT NULL COMMENT 'tickSize',
  `sync_time` datetime DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_exchange_symbol_type` (`exchange`,`symbol`,`market_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1047 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `trd_exchange_symbol`
--

--
-- Table structure for table `trd_order`
--

DROP TABLE IF EXISTS `trd_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `trd_order` (
  `id` bigint NOT NULL COMMENT '主键',
  `strategy_id` bigint DEFAULT NULL COMMENT '关联策略ID',
  `account_id` bigint NOT NULL COMMENT '关联账户ID',
  `signal_id` bigint DEFAULT NULL COMMENT '关联信号ID',
  `exchange` varchar(50) NOT NULL COMMENT '交易所',
  `symbol` varchar(50) NOT NULL COMMENT '交易对',
  `side` varchar(10) NOT NULL COMMENT 'BUY/SELL',
  `order_type` varchar(20) NOT NULL DEFAULT 'MARKET' COMMENT '订单类型: MARKET/LIMIT',
  `quantity` decimal(30,10) NOT NULL COMMENT '下单数量',
  `price` decimal(30,10) DEFAULT NULL COMMENT '委托价格(LIMIT)',
  `filled_quantity` decimal(30,10) DEFAULT '0.0000000000' COMMENT '成交数量',
  `filled_price` decimal(30,10) DEFAULT NULL COMMENT '成交均价',
  `fee` decimal(30,10) DEFAULT '0.0000000000' COMMENT '手续费',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '订单状态',
  `trade_mode` varchar(10) NOT NULL COMMENT 'LIVE/PAPER',
  `exchange_order_id` varchar(100) DEFAULT NULL COMMENT '交易所订单号',
  `error_msg` varchar(500) DEFAULT NULL COMMENT '错误信息',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` int NOT NULL DEFAULT '0',
  `market_type` varchar(10) DEFAULT 'SPOT' COMMENT 'SPOT/USDM/COINM',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_signal_id` (`signal_id`),
  KEY `idx_strategy_id` (`strategy_id`),
  KEY `idx_account_id` (`account_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交易订单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `trd_order`
--

--
-- Table structure for table `trd_position`
--

DROP TABLE IF EXISTS `trd_position`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `trd_position` (
  `id` bigint NOT NULL COMMENT '主键',
  `strategy_id` bigint NOT NULL COMMENT '关联策略ID',
  `account_id` bigint NOT NULL COMMENT '关联账户ID',
  `exchange` varchar(50) NOT NULL COMMENT '交易所',
  `symbol` varchar(50) NOT NULL COMMENT '交易对',
  `side` varchar(10) NOT NULL COMMENT 'LONG/SHORT',
  `quantity` decimal(30,10) NOT NULL DEFAULT '0.0000000000' COMMENT '持仓数量',
  `entry_price` decimal(30,10) NOT NULL COMMENT '开仓均价',
  `current_price` decimal(30,10) DEFAULT NULL COMMENT '当前价格',
  `unrealized_pnl` decimal(30,10) DEFAULT '0.0000000000' COMMENT '未实现盈亏',
  `realized_pnl` decimal(30,10) DEFAULT '0.0000000000' COMMENT '已实现盈亏',
  `stop_loss` decimal(30,10) DEFAULT NULL COMMENT '止损价',
  `take_profit` decimal(30,10) DEFAULT NULL COMMENT '止盈价',
  `close_price` decimal(30,10) DEFAULT NULL COMMENT '平仓价格',
  `closed_at` datetime DEFAULT NULL COMMENT '平仓时间',
  `status` varchar(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
  `trade_mode` varchar(10) NOT NULL COMMENT 'LIVE/PAPER',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` int NOT NULL DEFAULT '0',
  `market_type` varchar(10) DEFAULT 'SPOT' COMMENT 'SPOT/USDM/COINM',
  `leverage` int DEFAULT '1',
  `margin_type` varchar(10) DEFAULT 'ISOLATED' COMMENT 'ISOLATED/CROSS',
  `liquidation_price` decimal(30,10) DEFAULT NULL,
  `funding_rate` decimal(20,8) DEFAULT NULL,
  `stop_loss_stage` varchar(20) DEFAULT NULL COMMENT ': INITIAL/BREAKEVEN/TRAILING',
  `highest_price` decimal(30,10) DEFAULT NULL,
  `lowest_price` decimal(30,10) DEFAULT NULL,
  `open_bar_count` int NOT NULL DEFAULT '0' COMMENT 'K',
  PRIMARY KEY (`id`),
  KEY `idx_strategy_id` (`strategy_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持仓';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `trd_position`
--

--
-- Table structure for table `trd_symbol`
--

DROP TABLE IF EXISTS `trd_symbol`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `trd_symbol` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `symbol` varchar(30) NOT NULL,
  `base_asset` varchar(30) NOT NULL,
  `quote_asset` varchar(20) NOT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_symbol` (`symbol`)
) ENGINE=InnoDB AUTO_INCREMENT=676 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `trd_symbol`
--

--
-- Dumping routines for database 'vertex'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-04-06 18:05:11
