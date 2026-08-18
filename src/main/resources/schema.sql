-- 巡检记录表
CREATE TABLE IF NOT EXISTS inspection_record (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ip          TEXT    NOT NULL,
    hostname    TEXT,
    os_info     TEXT,
    cpu_info    TEXT,
    mem_info    TEXT,
    disk_info   TEXT,
    security_info TEXT,
    raw_json    TEXT,
    status      TEXT    DEFAULT 'SUCCESS',
    error_msg   TEXT,
    scan_time   TEXT    NOT NULL,
    created_at  TEXT    DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_inspection_ip ON inspection_record(ip);
CREATE INDEX IF NOT EXISTS idx_inspection_time ON inspection_record(scan_time);

-- 服务器资产表（采集后自动入库）
CREATE TABLE IF NOT EXISTS asset_server (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ip          TEXT    NOT NULL UNIQUE,
    hostname    TEXT,
    os_name     TEXT,
    os_version  TEXT,
    kernel      TEXT,
    cpu_model   TEXT,
    cpu_cores   INTEGER,
    mem_total   TEXT,
    disk_total  TEXT,
    status      TEXT    DEFAULT '在线',
    last_scan   TEXT,
    created_at  TEXT    DEFAULT (datetime('now', 'localtime')),
    updated_at  TEXT    DEFAULT (datetime('now', 'localtime'))
);
