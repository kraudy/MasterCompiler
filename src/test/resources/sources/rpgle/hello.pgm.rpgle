**Free
///
// Modern RPG Hello world
///
Ctl-opt DftActGrp(*no) main(main) BndDir('BNDHELLO');

Dcl-pr hello;
End-pr;

Dcl-pr bye;
End-pr;

Dcl-proc main;
  Dcl-pi *n;
  end-pi;

  hello();

  bye();

  return;

End-proc;