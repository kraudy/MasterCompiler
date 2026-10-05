**free
// MasterCompiler demo: NOMAIN module, built as module MCDCALC plus service program MCDCALC
ctl-opt nomain;

/copy mcdcalc_p.include.rpgle

dcl-proc mcdWithTax export;
  dcl-pi *n packed(9:2);
    price      packed(9:2) const;
    taxPercent packed(5:2) const;
  end-pi;

  return price + price * taxPercent / 100;
end-proc;
