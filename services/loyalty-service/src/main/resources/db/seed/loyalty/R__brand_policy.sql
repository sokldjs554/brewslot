INSERT INTO brand_point_policy (brand_id, earn_rate_bps, expiry_days)
VALUES (1, 300, 365),
       (2, 500, 180)
ON CONFLICT (brand_id) DO NOTHING;
