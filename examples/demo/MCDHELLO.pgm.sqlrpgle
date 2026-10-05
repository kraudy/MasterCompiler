**free
// MasterCompiler demo: reads MCDITEM with SQL and prices each item with service program MCDCALC
ctl-opt dftactgrp(*no) actgrp(*new) bnddir('MCDDEMO');

/copy mcdcalc_p.include.rpgle

dcl-s name  varchar(30);
dcl-s price packed(9:2);

exec sql declare items cursor for select NAME, PRICE from MCDITEM;
exec sql open items;
exec sql fetch items into :name, :price;
dow sqlcode = 0;
  dsply (%trim(name) + ' with tax: ' + %char(mcdWithTax(price: 15)));
  exec sql fetch items into :name, :price;
enddo;
exec sql close items;

*inlr = *on;
