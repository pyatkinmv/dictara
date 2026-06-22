CREATE TABLE plans (
    name         VARCHAR(20) PRIMARY KEY,
    display_name VARCHAR(50) NOT NULL,
    constraints  JSONB NOT NULL DEFAULT '{}'
);

INSERT INTO plans (name, display_name, constraints) VALUES
    ('free',      'Free',      '{"monthly_submissions": 10}'),
    ('unlimited', 'Unlimited', '{}');

ALTER TABLE users
    ADD COLUMN plan VARCHAR(20) NOT NULL DEFAULT 'free' REFERENCES plans(name);
