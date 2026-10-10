CREATE TABLE `t_seat` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `train_id` bigint DEFAULT NULL,
  `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `seat_number` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `seat_type` int DEFAULT NULL,
  `start_station` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `end_station` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price` int DEFAULT NULL,
  `seat_status` int DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_train_id` (`train_id`),
  KEY `idx_seat_query` (`train_id`,`seat_type`,`seat_status`,`start_station`,`end_station`),
  KEY `idx_seat_allocate` (`train_id`,`seat_type`,`seat_status`,`start_station`,`end_station`,`del_flag`,`carriage_number`,`seat_number`)
) ENGINE=InnoDB AUTO_INCREMENT=1685114673839570945 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci