CREATE TABLE admin_credentials (
    username VARCHAR(128) PRIMARY KEY,
    password_hash VARCHAR(512) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
