-- 데모/테스트용 카탈로그. 운영 환경에서는 spring.flyway.locations 에서 db/seed 를 제외한다.
INSERT INTO store (id, brand_id, name, open_time, close_time, slot_minutes, min_lead_minutes, max_advance_minutes)
VALUES (101, 1, '블루아워커피 역삼점', '00:00', '23:59', 5, 5, 1440),
       (102, 1, '블루아워커피 선릉점', '00:00', '23:59', 5, 5, 1440),
       (103, 1, '블루아워커피 삼성점', '00:00', '23:59', 5, 5, 1440),
       (104, 1, '블루아워커피 역삼역점', '00:00', '23:59', 5, 5, 1440),
       (105, 1, '블루아워커피 판교점', '00:00', '23:59', 5, 5, 1440),
       (201, 2, '모닝스탠드 강남역점', '00:00', '23:59', 5, 5, 1440)
ON CONFLICT (id) DO NOTHING;

-- 역삼점: 에스프레소 머신 2그룹 + 바리스타 2명 → 슬롯(5분)당 에스프레소 부하 6, 블렌더 1대 → 3, 브루바 → 2
INSERT INTO store_station_capacity (store_id, station, units_per_slot)
VALUES (101, 'ESPRESSO', 6), (101, 'BLENDER', 3), (101, 'BREW_BAR', 2),
       (102, 'ESPRESSO', 4), (102, 'BLENDER', 2), (102, 'BREW_BAR', 0),
       (103, 'ESPRESSO', 8), (103, 'BLENDER', 3), (103, 'BREW_BAR', 0),
       (104, 'ESPRESSO', 8), (104, 'BLENDER', 3), (104, 'BREW_BAR', 0),
       (105, 'ESPRESSO', 8), (105, 'BLENDER', 3), (105, 'BREW_BAR', 0),
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
       (2004, 102, '아이스 바닐라라떼', 5000, 'ESPRESSO', 2, 10, TRUE),
       (2005, 102, '망고 스무디', 6000, 'BLENDER', 2, 10, TRUE),
       -- 삼성점: 역삼점과 같은 메뉴 · 같은 가격 (에스프레소 머신 3그룹이라 용량이 더 크다). 핸드드립은 팔지 않는다.
       (4001, 103, '아메리카노(HOT)', 3500, 'ESPRESSO', 1, 5, TRUE),
       (4002, 103, '아이스 아메리카노', 3500, 'ESPRESSO', 1, 10, TRUE),
       (4003, 103, '카페라떼(HOT)', 4500, 'ESPRESSO', 2, 5, TRUE),
       (4004, 103, '아이스 바닐라라떼', 5000, 'ESPRESSO', 2, 10, TRUE),
       (4005, 103, '딸기 스무디', 6000, 'BLENDER', 3, 10, TRUE),
       -- 역삼역점: 역삼점과 같은 메뉴 · 가격, 매장 변경 자동 수락 / 판교점: 같은 메뉴지만 걸어갈 수 없는 거리
       (5001, 104, '아메리카노(HOT)', 3500, 'ESPRESSO', 1, 5, TRUE),
       (5002, 104, '아이스 아메리카노', 3500, 'ESPRESSO', 1, 10, TRUE),
       (5003, 104, '카페라떼(HOT)', 4500, 'ESPRESSO', 2, 5, TRUE),
       (5004, 104, '아이스 바닐라라떼', 5000, 'ESPRESSO', 2, 10, TRUE),
       (5005, 104, '딸기 스무디', 6000, 'BLENDER', 3, 10, TRUE),
       (6001, 105, '아메리카노(HOT)', 3500, 'ESPRESSO', 1, 5, TRUE),
       (6002, 105, '아이스 아메리카노', 3500, 'ESPRESSO', 1, 10, TRUE),
       (6004, 105, '아이스 바닐라라떼', 5000, 'ESPRESSO', 2, 10, TRUE),
       (3001, 201, '아메리카노', 2000, 'ESPRESSO', 1, 5, TRUE),
       (3002, 201, '아이스 아메리카노', 2000, 'ESPRESSO', 1, 10, TRUE),
       (3003, 201, '카페라떼', 3000, 'ESPRESSO', 2, 5, TRUE)
ON CONFLICT (id) DO NOTHING;

-- 매장 위치(데모 좌표)와 매장 변경 수락 방식. 역삼역점만 자동 수락, 나머지는 직원이 태블릿에서 수락한다.
UPDATE store SET latitude = 37.5007, longitude = 127.0365 WHERE id = 101;
UPDATE store SET latitude = 37.5051, longitude = 127.0421 WHERE id = 102;
UPDATE store SET latitude = 37.4968, longitude = 127.0418 WHERE id = 103;
UPDATE store SET latitude = 37.5006, longitude = 127.0333, auto_accept_transfers = TRUE WHERE id = 104;
UPDATE store SET latitude = 37.3947, longitude = 127.1112 WHERE id = 105;
UPDATE store SET latitude = 37.4979, longitude = 127.0276 WHERE id = 201;
