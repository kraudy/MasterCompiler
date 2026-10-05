-- MasterCompiler demo: a small item table
CREATE OR REPLACE TABLE MCDITEM (
  ITEM_ID CHAR(5)       NOT NULL PRIMARY KEY,
  NAME    VARCHAR(30)   NOT NULL,
  PRICE   DECIMAL(9, 2) NOT NULL
);

-- CREATE OR REPLACE keeps existing rows, so add the sample items only when missing: safe to run again
MERGE INTO MCDITEM T
  USING (VALUES ('A0001', 'Coffee', 4.50), ('A0002', 'Tea', 3.25)) S (ITEM_ID, NAME, PRICE)
  ON T.ITEM_ID = S.ITEM_ID
  WHEN NOT MATCHED THEN INSERT (ITEM_ID, NAME, PRICE) VALUES (S.ITEM_ID, S.NAME, S.PRICE);
