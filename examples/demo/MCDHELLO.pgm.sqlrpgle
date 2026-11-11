**free
// MasterCompiler demo: reads MCDITEM with SQL, prices each item with service program MCDCALC and prints the
// result to QPRINT. A call runs in a batch job (DSPLY shows nothing there): call_program and --call return the
// job's spooled output, so the lines appear in their answer.
ctl-opt dftactgrp(*no) actgrp(*new) bnddir('MCDDEMO');

dcl-f qprint printer(132);

/copy mcdcalc_p.include.rpgle

dcl-s name  varchar(30);
dcl-s price packed(9:2);
dcl-ds line len(132);
  text char(132);
end-ds;

exec sql declare items cursor for select NAME, PRICE from MCDITEM;
exec sql open items;
exec sql fetch items into :name, :price;
dow sqlcode = 0;
  text = %trim(name) + ' with tax: ' + %char(mcdWithTax(price: 15));
  write qprint line;
  exec sql fetch items into :name, :price;
enddo;
exec sql close items;

*inlr = *on;
