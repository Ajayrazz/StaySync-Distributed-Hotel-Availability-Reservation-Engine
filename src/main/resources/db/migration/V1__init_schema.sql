-- Enable spatial extensions if supported (gracefully handles environments without PostGIS)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Hotels Table
CREATE TABLE IF NOT EXISTS hotels (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    country VARCHAR(100) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    star_rating INT CHECK (star_rating BETWEEN 1 AND 5),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_hotels_city ON hotels(city);
CREATE INDEX IF NOT EXISTS idx_hotels_country ON hotels(country);

-- 2. Room Types Table (Categories of rooms sold by a hotel)
CREATE TABLE IF NOT EXISTS room_types (
    id BIGSERIAL PRIMARY KEY,
    hotel_id BIGINT NOT NULL REFERENCES hotels(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    base_price NUMERIC(10, 2) NOT NULL,
    capacity INT NOT NULL DEFAULT 2,
    total_inventory INT NOT NULL CHECK (total_inventory > 0),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_room_types_hotel ON room_types(hotel_id);

-- 3. Daily Inventory Table (The Core Availability Engine)
CREATE TABLE IF NOT EXISTS daily_inventory (
    id BIGSERIAL PRIMARY KEY,
    room_type_id BIGINT NOT NULL REFERENCES room_types(id) ON DELETE CASCADE,
    calendar_date DATE NOT NULL,
    available_count INT NOT NULL,
    reserved_count INT NOT NULL DEFAULT 0,
    price_multiplier NUMERIC(4, 2) NOT NULL DEFAULT 1.00,
    version BIGINT NOT NULL DEFAULT 0, -- For JPA Optimistic Locking
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_room_date UNIQUE (room_type_id, calendar_date),
    CONSTRAINT chk_reserved_available CHECK (reserved_count <= available_count AND reserved_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_inventory_lookup ON daily_inventory(room_type_id, calendar_date);

-- 4. Bookings Table (The Financial / Legal Reservation Contract)
CREATE TABLE IF NOT EXISTS bookings (
    id BIGSERIAL PRIMARY KEY,
    booking_reference VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(100) NOT NULL,
    hotel_id BIGINT NOT NULL REFERENCES hotels(id),
    room_type_id BIGINT NOT NULL REFERENCES room_types(id),
    check_in_date DATE NOT NULL,
    check_out_date DATE NOT NULL,
    total_price NUMERIC(10, 2) NOT NULL,
    status VARCHAR(30) NOT NULL, -- HOLD_PENDING, CONFIRMED, CANCELLED, EXPIRED
    idempotency_key VARCHAR(100) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_dates CHECK (check_out_date > check_in_date)
);

CREATE INDEX IF NOT EXISTS idx_bookings_user ON bookings(user_id);
CREATE INDEX IF NOT EXISTS idx_bookings_reference ON bookings(booking_reference);

-- 5. Reservation Holds Table (For temporary 15-minute checkout holds)
CREATE TABLE IF NOT EXISTS reservation_holds (
    id BIGSERIAL PRIMARY KEY,
    hold_id VARCHAR(64) NOT NULL UNIQUE,
    room_type_id BIGINT NOT NULL REFERENCES room_types(id),
    user_id VARCHAR(100) NOT NULL,
    check_in_date DATE NOT NULL,
    check_out_date DATE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, CONVERTED, EXPIRED
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_holds_expires_at ON reservation_holds(expires_at) WHERE status = 'ACTIVE';

-- 6. Outbox Events Table (For Transactional Outbox Pattern with Kafka)
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, PUBLISHED, FAILED
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_events(created_at) WHERE status = 'PENDING';
