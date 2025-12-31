**Free
///
// RPG NoMain Module with only 1 exported procedure.
///
Ctl-opt NoMain;

Dcl-proc bye export;
  Dcl-pi *n;
  end-pi;

  dsply 'Bye world!';

  return;

End-proc;