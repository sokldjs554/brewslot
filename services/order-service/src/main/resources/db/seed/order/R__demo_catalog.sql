-- 데모/테스트용 카탈로그. 운영 환경에서는 spring.flyway.locations 에서 db/seed 를 제외한다.
INSERT INTO store (id, brand_id, name, open_time, close_time, slot_minutes, min_lead_minutes, max_advance_minutes)
VALUES (101, 1, '블루아워커피 역삼점', '00:00', '23:59', 5, 5, 1440),
       (102, 1, '블루아워커피 선릉점', '00:00', '23:59', 5, 5, 1440),
       (201, 2, '모닝스탠드 강남역점', '00:00', '23:59', 5, 5, 1440)
ON CONFLICT (id) DO NOTHING;

-- 역삼점: 에스프레소 머신 2그룹 + 바리스타 2명 → 슬롯(5분)당 에스프레소 부하 6, 블렌더 1대 → 3, 브루바 → 2
INSERT INTO store_station_capacity (store_id, station, units_per_slot)
VALUES (101, 'ESPRESSO', 6), (101, 'BLENDER', 3), (101, 'BREW_BAR', 2),
       (102, 'ESPRESSO', 4), (102, 'BLENDER', 2), (102, 'BREW_BAR', 0),
       (201, 'ESPRESSO', 8), (201, 'BLENDER', 0), (201, 'BREW_BAR', 0)
ON CONFLICT DO NOTHING;

INSERT INTO menu_item (id, store_id, name, price, station, load_units, freshness_minutes, available)
VALUES (1001, 101, '아메리카노(HOT)', 3500, 'ESPRESSO', 1, 5, TRUE),
       (1002, 101, '아이스 아메리카노', 3500, 'ESPRESSO', 1, 10, TRUE),
       (1003, 101, '카페라떼(HOT)', 4500, 'ESPRESSO', 2, 5, TRUE),
       (1004, 101, '아이스 바닐라라떼', 5000, 'ESPRESSO', 2, 10, TRUE),
       (1005, 101, '딸기 스무디', 6000, 'BLENDER', 3, 10, TRUE),
       (1006, 101, '핸드드립(에티오피아)', 6500, 'BREW_BAR', 2, 10, TRUE),
       (1007, 101, '시즌 한정 밤라떼', 6000, 'ESPRESSO', 2, 5, FALSE),
       (2001, 102, '아메리카노(HOT)', 3500, 'ESPRESSO', 1, 5, TRUE),
       (2002, 102, '아이스 아메리카노', 3500, 'ESPRESSO', 1, 10, TRUE),
       (2003, 102, '카페라떼(HOT)', 4500, 'ESPRESSO', 2, 5, TRUE),
       (2005, 102, '망고 스무디', 6000, 'BLENDER', 2, 10, TRUE),
       (3001, 201, '아메리카노', 2000, 'ESPRESSO', 1, 5, TRUE),
       (3002, 201, '아이스 아메리카노', 2000, 'ESPRESSO', 1, 10, TRUE),
       (3003, 201, '카페라떼', 3000, 'ESPRESSO', 2, 5, TRUE)
ON CONFLICT (id) DO NOTHING;
