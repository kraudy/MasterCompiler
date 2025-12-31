**Free
///
// RPG Module with only 1 export procedure, no main.
///
Ctl-opt NoMain;

Dcl-proc hello export;
  Dcl-pi *n;
  end-pi;

  dsply 'Hello world!';

  return;

End-proc;