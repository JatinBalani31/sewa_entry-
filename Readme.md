System Requirements & Master Execution Document: Local Attendance Monitoring System1. Executive SummaryThis document specifies the technical architecture, database schema, operational protocols, phase-wise execution plan, and developer hand-off directives for a dual-mode Local Attendance Monitoring System.The system provides robust attendance tracking through two core pathways:Personal Mobile App (GPS Geofenced) – Individual verification based on real-time location.Shared Kiosk Tablet (Manual PIN / Signature) – On-site fallback for employees who do not have their mobile devices.All data is synchronized to a central local/cloud server managed via a Web Admin Console, capable of real-time monitoring, manual admin overrides, and structured Excel report exports.2. Master System Architecture.

                                   +-------------------------------------+
                                    |         ADMIN CONSOLE               |
                                    |   (React / Next.js Web App)         |
                                    | - Employee & Site Management        |
                                    | - Live Attendance Dashboard         |
                                    | - Manual Overrides & Approvals      |
                                    | - Excel Summary Generator           |
                                    +------------------+------------------+
                                                       |
                                                       | HTTPS / JSON (JWT Auth)
                                                       v
+---------------------------------------------------------------------------------------------------+
|                                        BACKEND API SERVER                                         |
|                                    (Node.js / Python FastAPI)                                       |
|                                                                                                   |
|   +-------------------+    +--------------------+    +--------------------+    +------------------+   |
|   | Auth & JWT Module |    | Geofence Validation|    | Attendance Service |    | Excel Engine     |   |
|   +-------------------+    +--------------------+    +--------------------+    +------------------+   |
+--------------------------------------------------+------------------------------------------------+
                                                   |
                                                   | ORM / Database Queries
                                                   v
+---------------------------------------------------------------------------------------------------+
|                                      RELATIONAL DATABASE                                          |
|                                         (PostgreSQL)                                              |
|   - Table: users (Hashed PINs, Roles, Device IDs)                                                 |
|   - Table: sites (Lat, Long, Allowed Radius)                                                      |
|   - Table: attendance_logs (Timestamps, Method, Location, Admin Audits)                           |
+---------------------------------------------------------------------------------------------------+
                                         | HTTPS / JSON (JWT Auth)
                               +-------------------+-------------------+
                               |                                       |
                               v                                       v
+----------------------------------------------+ +--------------------------------------------------+
|           ANDROID APP: CLIENT MODE           | |             ANDROID APP: KIOSK MODE              |
|           (Personal Mobile Device)           | |            (Shared Reception Tablet)             |
| - GPS Location Capture                       | | - Locked Terminal Interface                      |
| - Geofence Boundary Verification             | | - Secret PIN Verification                        |
| - Server Network Time Enforcement            | | - Touchscreen Signature Capture                  |
| - Mock Location Detection Guard              | | - Offline Local Cache & Background Sync          |
+----------------------------------------------+ +--------------------------------------------------+
3. Detailed Technical ComponentsA. Web Admin ConsoleRole-Based Access Control (RBAC): Super Admin, Branch Managers, and Employees.Geofence Management: Interactive map interface to define work locations with target latitude, longitude, and accuracy radius (meters).Log Management & Overrides: Audit panel to manually insert check-in/out logs for special cases, requiring mandatory administrative notes.Data Export Engine: Server-side engine to produce formatted multi-tab .xlsx reports (Daily Logs, Monthly Summaries, Exception Reports).B. Android App: Individual GPS ClientLocation Provider: Google Fused Location Provider API.Verification Payload: Sends Latitude, Longitude, Accuracy, Device Timestamp, and Hardware ID.Spoofing Guardrails:Server-side validation of coordinates against defined site radius.Rejection of client timestamps (all logs use server atomic time).Native Android check for isFromMockProvider() to prevent fake location apps.C. Android App: Kiosk Tablet StationStation Mode: Locked full-screen activity (Kiosk mode) preventing access to device settings.PIN & Signature Fallback: Allows selection of user ID, PIN challenge, and optional digital signature capture via on-screen canvas.Offline Resilience: Local SQLite/Room storage caches logs if local Wi-Fi drops, automatically pushing records when connectivity is restored.4. Complete Database Schema (PostgreSQL DDL)SQL-- Enable PostGIS extension if advanced spatial queries are required in future
-- CREATE EXTENSION IF NOT EXISTS postgis;

-- 1. USERS TABLE
CREATE TABLE users (
    id VARCHAR(50) PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    secret_pin_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'EMPLOYEE', -- 'ADMIN', 'MANAGER', 'EMPLOYEE'
    device_id VARCHAR(100),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. SITES (GEOFENCE LOCATIONS) TABLE
CREATE TABLE sites (
    id SERIAL PRIMARY KEY,
    site_name VARCHAR(100) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    radius_meters INT NOT NULL DEFAULT 50,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. ATTENDANCE LOGS TABLE
CREATE TABLE attendance_logs (
    id SERIAL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    site_id INT REFERENCES sites(id) ON DELETE SET NULL,
    action_type VARCHAR(10) NOT NULL CHECK (action_type IN ('IN', 'OUT')),
    verification_method VARCHAR(20) NOT NULL CHECK (verification_method IN ('GPS', 'MANUAL_PIN', 'SIGNATURE', 'ADMIN_OVERRIDE')),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    signature_blob TEXT, -- Base64 encoded signature if applied
    verified_by_admin_id VARCHAR(50) REFERENCES users(id),
    admin_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- INDEXES FOR PERFORMANCE
CREATE INDEX idx_attendance_user_id ON attendance_logs(user_id);
CREATE INDEX idx_attendance_created_at ON attendance_logs(created_at);
CREATE INDEX idx_attendance_site ON attendance_logs(site_id);
5. End-to-End Phase-Wise Implementation Plan+------------------------------------------------------------------------------------+
|                                TIMELINE & PHASES                                   |
+------------------------------------------------------------------------------------+
| Phase 1: Core API & Database Setup             [Weeks 1-2]                         |
| Phase 2: Web Admin Console Development         [Weeks 3-4]                         |
| Phase 3: Android Personal Client (GPS)         [Weeks 5-6]                         |
| Phase 4: Android Kiosk Terminal (PIN/Tablet)   [Weeks 7-8]                         |
| Phase 5: Security Hardening & Excel Engine     [Week 9]                            |
| Phase 6: Field Testing & Deployment            [Week 10]                           |
+------------------------------------------------------------------------------------+
Phase 1: Core API & Database Setup (Weeks 1-2)Deploy PostgreSQL instance and run DDL scripts.Build Node.js/Python backend with JWT authentication (/auth/login).Implement CRUD endpoints for Users and Sites.Implement core /attendance/log endpoints supporting GPS, PIN, and ADMIN_OVERRIDE methods.Phase 2: Web Admin Console Development (Weeks 3-4)Construct dashboard UI for employee management and initial PIN configuration.Integrate interactive map component to create and view geofenced zones.Build live attendance logs view with manual override modals.Phase 3: Android Personal Client - GPS Module (Weeks 5-6)Configure Google Fused Location API to fetch current device location.Build UI for single-tap Clock IN / Clock OUT.Implement client-side mock location detection checks.Hook API request payloads to the backend /attendance/log endpoint.Phase 4: Android Kiosk Terminal - Shared Tablet (Weeks 7-8)Create Kiosk mode layout with pinned interface.Build Employee ID search dropdown + numeric keypad for PIN authorization.Implement HTML5 canvas signature viewer for entry sign-off.Implement local SQLite caching for offline clock-in storage and sync worker.Phase 5: Security Hardening & Excel Engine (Week 9)Add rate limiting and PIN lockout mechanics (3 failed attempts = 5 min cooldown).Construct backend reporting worker using exceljs or pandas.Format Excel export sheet with auto-filtered columns, formulas for daily hours worked, and color-coded verification flags.Phase 6: Field Testing & Deployment (Week 10)Perform geofence boundary tests (testing accuracy at perimeter limits).Simulate multi-user peak load tests.Deploy backend to production host and install APKs on client/kiosk hardware.6. Developer & AI Agent Hand-Off DirectivesMarkdown### AGENT INSTRUCTION SPECIFICATION

SYSTEM: Attendance System Construction (Client Mobile + Kiosk Tablet + Admin Console)

1. BACKEND DIRECTIVES (Node.js/Express or Python FastAPI):
   - Secure all endpoints using JWT bearer tokens.
   - Hash secret PINs with bcrypt before saving to the database.
   - Validate geofence boundaries on the SERVER using the Haversine formula. Do not trust client-side boolean checks.
   - Force all timestamps to be generated by the database/server. Ignore client payload timestamps.

2. GEOFENCE CALCULATION LOGIC (Haversine Formula):
   Calculate the distance R between user (lat1, lon1) and site (lat2, lon2). 
   If R > site.radius_meters, return 400 Bad Request ("Outside Geofence Area").

3. ANDROID APPLICATION DIRECTIVES:
   - Provide two operational modes toggleable during setup: "PERSONAL_CLIENT" and "TABLET_KIOSK".
   - In PERSONAL_CLIENT mode, query location with HIGH_ACCURACY priority. Reject if location.isFromMockProvider() is TRUE.
   - In TABLET_KIOSK mode, lock screen orientation to Landscape and disable hardware back button navigation.

4. EXCEL EXPORT SPECIFICATION:
   Provide an endpoint `GET /api/reports/excel?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD` that returns an `.xlsx` file with headers:
   [Log ID | User ID | Employee Name | Date | Time | Type (IN/OUT) | Verification Method | Site Name | Verified By Admin]

7. Sample Data Export Layout (Excel Sheet Output)Log IDUser IDEmployee NameDateTimeTypeVerification MethodSite NameVerified By AdminNotes1001EMP004John Doe2026-08-2808:58:12INGPSMain OfficeN/AValid Geofence1002EMP012Jane Smith2026-08-2809:02:45INMANUAL_PINKiosk Station 1N/APhone Left Home1003EMP007Alex Wong2026-08-2809:15:00INADMIN_OVERRIDEMain OfficeADM001Approved late arrival1004EMP004John Doe2026-08-2817:01:30OUTGPSMain OfficeN/AValid Geofence