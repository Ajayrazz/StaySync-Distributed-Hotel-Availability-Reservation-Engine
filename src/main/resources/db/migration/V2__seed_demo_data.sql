-- Seed Hotels
INSERT INTO hotels (name, description, address, city, country, latitude, longitude, star_rating) VALUES
('The Plaza Hotel', 'Iconic luxury hotel overlooking Central Park with classic elegance and world-class dining.', '768 5th Ave', 'New York', 'USA', 40.7645, -73.9744, 5),
('Hotel Ritz Paris', 'Palatial hotel offering historic French decor, Michelin-starred dining, and spa.', '15 Place Vendôme', 'Paris', 'France', 48.8682, 2.3294, 5),
('Park Hyatt Tokyo', 'Towering luxury hotel in Shinjuku featured in Lost in Translation, offering Mount Fuji views.', '3-7-1-2 Nishi-Shinjuku', 'Tokyo', 'Japan', 35.6853, 139.6914, 5);

-- Seed Room Types
INSERT INTO room_types (hotel_id, name, description, base_price, capacity, total_inventory) VALUES
(1, 'Deluxe King Suite', 'Spacious suite with Central Park views and marble bathroom', 650.00, 2, 5),
(1, 'Superior Double Room', 'Comfortable double room with vintage Manhattan decor', 420.00, 4, 10),
(2, 'Prestige Vendome Room', 'Opulent French styling with king bed and private terrace', 850.00, 2, 4),
(3, 'Park King Room', 'High-floor room with panoramic views of Tokyo skyline', 550.00, 2, 6);

-- Seed 90 days of Daily Inventory for each room type starting from 2026-10-01
DO $$
DECLARE
    r RECORD;
    d DATE;
BEGIN
    FOR r IN SELECT id, total_inventory FROM room_types LOOP
        FOR i IN 0..90 LOOP
            d := CURRENT_DATE + i;
            INSERT INTO daily_inventory (room_type_id, calendar_date, available_count, reserved_count, price_multiplier)
            VALUES (r.id, d, r.total_inventory, 0, 1.00)
            ON CONFLICT (room_type_id, calendar_date) DO NOTHING;
        END LOOP;
    END LOOP;
END $$;
