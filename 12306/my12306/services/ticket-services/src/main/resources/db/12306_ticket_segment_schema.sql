-- Run against the selected ticket database with inventory writers stopped.
-- CREATE IF NOT EXISTS is additive; data population is performed by InventoryMigrationTool.
CREATE TABLE IF NOT EXISTS t_inventory_station (
  train_id BIGINT NOT NULL,
  station_index INT NOT NULL,
  station_name VARCHAR(256) NOT NULL,
  PRIMARY KEY (train_id,station_index),
  UNIQUE KEY uk_inventory_station_name (train_id,station_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_physical_seat (
  id BIGINT NOT NULL AUTO_INCREMENT,
  train_id BIGINT NOT NULL,
  seat_type INT NOT NULL,
  carriage_number VARCHAR(64) NOT NULL,
  seat_number VARCHAR(64) NOT NULL,
  del_flag INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_physical_coordinate (train_id,carriage_number,seat_number),
  KEY idx_physical_allocate (train_id,seat_type,del_flag,carriage_number,seat_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_seat_segment (
  seat_id BIGINT NOT NULL,
  segment_index INT NOT NULL,
  seat_status INT NOT NULL DEFAULT 0,
  ticket_id BIGINT NULL,
  order_sn VARCHAR(64) NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (seat_id,segment_index),
  KEY idx_segment_owner (ticket_id,order_sn,segment_index),
  CONSTRAINT fk_segment_physical FOREIGN KEY (seat_id) REFERENCES t_physical_seat(id),
  CONSTRAINT chk_segment_owner CHECK (
    (seat_status=0 AND ticket_id IS NULL AND order_sn IS NULL) OR
    (seat_status IN (1,2) AND ticket_id IS NOT NULL AND order_sn IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_inventory_cache_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  train_id BIGINT NOT NULL,
  start_index INT NOT NULL,
  end_index INT NOT NULL,
  invalidate_bucket BOOLEAN NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
