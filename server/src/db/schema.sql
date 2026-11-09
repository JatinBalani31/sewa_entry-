-- ============================================================================
-- SEWA ENTRY :: Sewadar attendance for a weekly sewa roster
--
-- The system records, for every Sewadar, the in-time and out-time of each sewa
-- day (Wednesday and Sunday by default), organised by department, and exports
-- that record per department.
--
-- One row per Sewadar per sewa day — not a stream of IN/OUT events. A sewa day
-- has exactly one arrival and one departure, so the register shape and the
-- table shape are the same thing, and "who has not signed out yet" is a plain
-- NULL check rather than a pairing exercise.
-- ============================================================================

-- 1. DEPARTMENTS
--    Accounts, BAV, Traffic, Luggage, Mobile, Safai, Transport, and whatever
--    else the sangat adds later. Every department names an HOD; the HOD holds
--    no extra permissions today, the field records who it is.
CREATE TABLE IF NOT EXISTS departments (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    code VARCHAR(20),
    description TEXT,
    hod_sewadar_id VARCHAR(50),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. SEWADARS
--    Volunteers. Two credentials, for two different jobs:
--      secret_pin_hash — a short numeric PIN, typed on the counter tablet's
--                        keypad by whoever is next in the queue.
--      password_hash   — set only for administrators. When it is present it is
--                        the ONLY thing that opens the console; the PIN then
--                        works at the kiosk alone and cannot be used to sign in.
CREATE TABLE IF NOT EXISTS sewadars (
    id VARCHAR(50) PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    organization VARCHAR(100),
    email VARCHAR(100) UNIQUE,
    department_id INT REFERENCES departments(id) ON DELETE SET NULL,
    secret_pin_hash VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    role VARCHAR(20) NOT NULL DEFAULT 'SEWADAR' CHECK (role IN ('ADMIN', 'SEWADAR')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    failed_pin_attempts INT NOT NULL DEFAULT 0,
    pin_locked_until TIMESTAMP WITH TIME ZONE,
    joined_on DATE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE sewadars ADD COLUMN IF NOT EXISTS organization VARCHAR(100);

-- 3. ATTENDANCE
--    department_id is a snapshot taken when the record is created, so a
--    Sewadar moving department later does not silently rewrite past reports.
--
--    in_time / out_time are what the register shows and are freely adjustable
--    within the configured tolerance. in_tapped_at / out_tapped_at keep the
--    untouched moment the button was actually pressed, so a report can always
--    show what was adjusted and by how much.
CREATE TABLE IF NOT EXISTS attendance (
    id SERIAL PRIMARY KEY,
    sewadar_id VARCHAR(50) NOT NULL REFERENCES sewadars(id) ON DELETE CASCADE,
    department_id INT REFERENCES departments(id) ON DELETE SET NULL,
    sewa_date DATE NOT NULL,
    in_time TIMESTAMP WITH TIME ZONE,
    out_time TIMESTAMP WITH TIME ZONE,
    -- Spelled out with the NULL case rather than a bare IN (...): an unset
    -- source is normal (nobody has signed out yet), and a bare IN evaluates to
    -- NULL there, which not every engine treats as a pass.
    in_source VARCHAR(20) CHECK (in_source IS NULL OR in_source IN ('TAP', 'MANUAL', 'KIOSK', 'ADMIN')),
    out_source VARCHAR(20) CHECK (out_source IS NULL OR out_source IN ('TAP', 'MANUAL', 'KIOSK', 'ADMIN')),
    in_tapped_at TIMESTAMP WITH TIME ZONE,
    out_tapped_at TIMESTAMP WITH TIME ZONE,
    signature_blob TEXT,
    notes TEXT,
    recorded_by VARCHAR(50) REFERENCES sewadars(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (sewadar_id, sewa_date)
);

-- INDEXES
CREATE INDEX IF NOT EXISTS idx_attendance_sewadar ON attendance(sewadar_id);
CREATE INDEX IF NOT EXISTS idx_attendance_date ON attendance(sewa_date);
CREATE INDEX IF NOT EXISTS idx_attendance_department ON attendance(department_id);
CREATE INDEX IF NOT EXISTS idx_sewadars_department ON sewadars(department_id);

-- The HOD reference is added after both tables exist, because the two tables
-- point at each other.
ALTER TABLE departments
  ADD CONSTRAINT fk_department_hod
  FOREIGN KEY (hod_sewadar_id) REFERENCES sewadars(id) ON DELETE SET NULL;
