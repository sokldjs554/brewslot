-- database-per-service: 서비스는 서로의 DB 를 절대 직접 읽지 않는다 (Kafka 이벤트 / HTTP 로만 통신)
CREATE DATABASE order_db;
CREATE DATABASE payment_db;
CREATE DATABASE loyalty_db;
CREATE DATABASE settlement_db;
