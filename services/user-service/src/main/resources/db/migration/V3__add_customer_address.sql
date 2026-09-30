ALTER TABLE customers
    ADD COLUMN address_line1 VARCHAR(150),
    ADD COLUMN address_line2 VARCHAR(150),
    ADD COLUMN city          VARCHAR(100),
    ADD COLUMN state         VARCHAR(100),
    ADD COLUMN postal_code   VARCHAR(20);
