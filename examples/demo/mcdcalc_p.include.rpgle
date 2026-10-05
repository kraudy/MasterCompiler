**free
// MasterCompiler demo: prototype of the procedure exported by service program MCDCALC
dcl-pr mcdWithTax packed(9:2);
  price      packed(9:2) const;
  taxPercent packed(5:2) const;
end-pr;
