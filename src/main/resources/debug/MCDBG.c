/*
 * MCDBG: MasterCompiler's debug helper for the debug_program tool. One program, two roles:
 *   driver (called by MC):  MCDBG 'S' pgm(20) lines(2000) vars(1000) maxhits(5) out(2000)  start + breakpoints
 *                           MCDBG 'E' out(65000)                                             end + the stops
 *   program-stop handler (called by the system, 7 parameters): evaluates the variables, records them and returns,
 *   so the program goes on by itself. It never waits for anything.
 * Created with ACTGRP(QTEDBGAG): that activation group stays while the job is in debug, so the static state below
 * lives from 'S' through the stops to 'E'. Text MC reads: lines "STOP module line hit", "VAR name=value",
 * "NOTE text", "ERR text".
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <qtedbgs.h>

#define MAX_MODULES 32
#define MAX_BREAKS  20
#define MAX_VARS    20
#define RESULT_SIZE 64000

typedef struct {
  int  bytesProvided;
  int  bytesAvailable;
  char msgId[7];
  char reserved;
  char data[256];
} ErrorCode;

typedef struct {
  char module[10];
  long viewId;
  char compiler[20];
} View;

typedef struct {
  char module[10];
  long viewId;
  long line;       /* in the source (text) view: what the user named */
  long stmt;       /* in the statement view: what the stop handler gets */
  int  hits;
  int  active;
} Break;

static char   program[20];
static View   views[MAX_MODULES];
static int    viewCount;
static Break  breaks[MAX_BREAKS];
static int    breakCount;
static char   vars[MAX_VARS][128];
static int    varCount;
static int    maxHits = 10;
static int    totalStops;
static char   result[RESULT_SIZE];
static int    resultLen;
static int    truncated;

static void add(const char *text) {
  int len = strlen(text);
  if (truncated) return;
  if (resultLen + len + 1 >= RESULT_SIZE - 40) {
    strcpy(result + resultLen, "NOTE results cut: buffer full\n");
    resultLen += strlen("NOTE results cut: buffer full\n");
    truncated = 1;
    return;
  }
  memcpy(result + resultLen, text, len);
  resultLen += len;
  result[resultLen++] = '\n';
  result[resultLen] = '\0';
}

static void trimCopy(char *to, const char *from, int len) {
  memcpy(to, from, len);
  to[len] = '\0';
  while (len > 0 && (to[len - 1] == ' ' || to[len - 1] == '\0')) to[--len] = '\0';
}

static void noteError(const char *what, ErrorCode *err) {
  char line[400];
  char msg[8];
  trimCopy(msg, err->msgId, 7);
  sprintf(line, "ERR %s: %s", what, msg);
  add(line);
}

static void clearError(ErrorCode *err) {
  memset(err, 0, sizeof(ErrorCode));
  err->bytesProvided = sizeof(ErrorCode);
}

/* "S": start debug, register a source view per module, add the breakpoints. lines: "MOD1:10,20;MOD2:5" or "10,20" */
static void start(char *pgm, char *lines, char *varList, char *hits, char *out) {
  ErrorCode err;
  char buffer[32000];
  _TE_VEWL0100_T *list = (_TE_VEWL0100_T *) buffer;
  long length = sizeof(buffer);
  char *bigger = NULL;
  char library[10];
  char handler[20];
  char spec[2001];
  char names[1001];
  char *token;
  int i;

  memset(views, 0, sizeof(views));
  memset(breaks, 0, sizeof(breaks));
  viewCount = breakCount = varCount = totalStops = resultLen = truncated = 0;
  result[0] = '\0';
  memcpy(program, pgm, 20);
  maxHits = atoi(hits);
  if (maxHits < 1 || maxHits > 50) maxHits = 10;

  memcpy(handler, "MCDBG     ", 10);
  memcpy(handler + 10, pgm + 10, 10);  /* MCDBG lives next to the program */
  clearError(&err);
  QteStartSourceDebug((_TE_OBJLIB_T *) handler, (_TE_ERROR_CODE_T *) &err);
  if (err.bytesAvailable > 0 && memcmp(err.msgId, "CPF9540", 7) == 0) {  /* still in debug from an earlier run */
    clearError(&err);
    QteEndSourceDebug((_TE_ERROR_CODE_T *) &err);
    clearError(&err);
    QteStartSourceDebug((_TE_OBJLIB_T *) handler, (_TE_ERROR_CODE_T *) &err);
  }
  if (err.bytesAvailable > 0) noteError("QteStartSourceDebug", &err);

  clearError(&err);
  QteRetrieveModuleViews(buffer, &length, "VEWL0100", pgm, "*PGM      ", "*ALL      ", library,
                         (_TE_ERROR_CODE_T *) &err);
  if (err.bytesAvailable == 0 && list->BytesAvailable > length && (bigger = malloc(list->BytesAvailable)) != NULL) {
    length = list->BytesAvailable;  /* more views than fit: once more with room for all */
    list = (_TE_VEWL0100_T *) bigger;
    clearError(&err);
    QteRetrieveModuleViews(bigger, &length, "VEWL0100", pgm, "*PGM      ", "*ALL      ", library,
                           (_TE_ERROR_CODE_T *) &err);
  }
  if (err.bytesAvailable > 0) {
    noteError("QteRetrieveModuleViews", &err);
  } else {
    /* per module, the first source view (*TEXT) that is not a copy view: the lines of the source file */
    for (i = 0; i < list->NumberElements && viewCount < MAX_MODULES; i++) {
      char desc[51];
      int known = 0, v;
      if (memcmp(list->Element[i].ViewType, "*TEXT", 5) != 0) continue;
      trimCopy(desc, list->Element[i].ViewDescription, sizeof(list->Element[i].ViewDescription));
      if (strstr(desc, "Copy") || strstr(desc, "COPY") || strstr(desc, "Include")) continue;
      for (v = 0; v < viewCount; v++) if (memcmp(views[v].module, list->Element[i].ModuleName, 10) == 0) known = 1;
      if (known) continue;
      {
        long viewId, viewLines;
        char rlib[10];
        _TE_TIMESTAMP_T stamp;
        long number = list->Element[i].ViewNumber;
        clearError(&err);
        QteRegisterDebugView(&viewId, &viewLines, rlib, stamp, pgm, "*PGM      ", list->Element[i].ModuleName,
                             &number, (_TE_ERROR_CODE_T *) &err);
        if (err.bytesAvailable > 0) { noteError("QteRegisterDebugView", &err); continue; }
        memcpy(views[viewCount].module, list->Element[i].ModuleName, 10);
        views[viewCount].viewId = viewId;
        memcpy(views[viewCount].compiler, list->Element[i].CompilerID, 20);
        viewCount++;
      }
    }
  }
  free(bigger);
  if (viewCount == 0) add("ERR no source view: compile with DBGVIEW(*SOURCE) or *ALL");

  /* breakpoints */
  trimCopy(spec, lines, 2000);
  for (token = strtok(spec, ";"); token && viewCount > 0; token = strtok(NULL, ";")) {
    char module[11];
    char *colon = strchr(token, ':');
    char *numbers = colon ? colon + 1 : token;
    View *view = &views[0];
    char *n;
    memset(module, ' ', 10);
    module[10] = '\0';
    if (colon) {
      int k;
      memcpy(module, token, (colon - token) > 10 ? 10 : (colon - token));
      view = NULL;
      for (k = 0; k < viewCount; k++) if (memcmp(views[k].module, module, 10) == 0) view = &views[k];
      if (!view) { char line[100]; sprintf(line, "ERR no source view for module %.10s", module); add(line); continue; }
    }
    for (n = numbers; *n && breakCount < MAX_BREAKS;) {
      long line = strtol(n, &n, 10);
      long column = 1, stmt = 0;
      if (line > 0) {
        clearError(&err);
        QteAddBreakpoint(&view->viewId, &line, &column, &stmt, (_TE_ERROR_CODE_T *) &err);
        if (err.bytesAvailable > 0) {
          char what[60];
          sprintf(what, "breakpoint %.10s %ld", view->module, line);
          noteError(what, &err);
        } else {
          memcpy(breaks[breakCount].module, view->module, 10);
          breaks[breakCount].viewId = view->viewId;
          breaks[breakCount].line = line;
          breaks[breakCount].stmt = stmt;
          breaks[breakCount].active = 1;
          breakCount++;
        }
      }
      while (*n == ',' || *n == ' ') n++;
      if (*n && (*n < '0' || *n > '9')) break;
    }
  }

  trimCopy(names, varList, 1000);
  for (token = strtok(names, " ,"); token && varCount < MAX_VARS; token = strtok(NULL, " ,")) {
    strncpy(vars[varCount], token, 127);
    varCount++;
  }

  {  /* what is set: "BREAK module line stmt" lines, then the errors so far */
    char text[2001];
    int used = 0, b;
    text[0] = '\0';
    for (b = 0; b < breakCount && used < 1900; b++) {
      used += sprintf(text + used, "BREAK %.10s %ld %ld\n", breaks[b].module, breaks[b].line, breaks[b].stmt);
    }
    if (resultLen > 0 && used < 1900) {
      int room = 1999 - used;
      int take = resultLen < room ? resultLen : room;
      memcpy(text + used, result, take);
      used += take;
    }
    memset(out, ' ', 2000);
    memcpy(out, text, used);
  }
}

/* EVAL one variable; its values (a data structure gives one per subfield) as VAR lines */
static void evaluate(View *view, const char *name) {
  ErrorCode err;
  char command[160];
  static char buffer[32000];
  long length = sizeof(buffer);
  long commandLength;
  _TE_RESULT_BUFFER_T *res = (_TE_RESULT_BUFFER_T *) buffer;
  unsigned long returned;
  char text[200];
  unsigned long i;
  int any = 0;

  sprintf(command, "EVAL %s", name);
  commandLength = strlen(command);
  clearError(&err);
  QteSubmitDebugCommand(buffer, &length, &view->viewId, command, &commandLength, view->compiler,
                        (_TE_ERROR_CODE_T *) &err);
  if (err.bytesAvailable > 0) {
    char msg[8];
    char line[200];
    trimCopy(msg, err.msgId, 7);
    sprintf(line, "VAR %s=?error %s", name, msg);
    add(line);
    return;
  }
  strcpy(text, name);
  returned = res->Header.BytesReturned;
  if (returned > sizeof(buffer)) returned = sizeof(buffer);
  if (res->Header.BytesAvailable > returned) {
    char line[200];
    sprintf(line, "NOTE %s: the value is larger than the %d bytes read; what fits is shown", name, (int) sizeof(buffer));
    add(line);
  }
  for (i = 0; i < res->Header.EntryCount
       && sizeof(_TE_OUTPUT_BUFFER_HDR_T) + (i + 1) * sizeof(_TE_RESULTS_T) <= returned; i++) {
    _TE_RESULTS_T *r = &res->Data[i];
    if (r->ResultKind == _TE_kExpressionTextR) {
      unsigned long len = r->V.ExpressionText.ExpressionTextLength;
      if (r->V.ExpressionText.oExpressionText >= returned) continue;
      if (len > returned - r->V.ExpressionText.oExpressionText) len = returned - r->V.ExpressionText.oExpressionText;
      if (len > 150) len = 150;
      memcpy(text, buffer + r->V.ExpressionText.oExpressionText, len);
      text[len] = '\0';
    } else if (r->ResultKind == _TE_kExpressionValueR) {
      char line[1200];
      unsigned long len = r->V.ExpressionValue.ExpressionValueLength;
      if (r->V.ExpressionValue.oExpressionValue >= returned) continue;
      if (len > returned - r->V.ExpressionValue.oExpressionValue) len = returned - r->V.ExpressionValue.oExpressionValue;
      if (len > 1000) {
        char note[200];
        sprintf(note, "NOTE %s: value cut to 1000 of %lu characters", text, len);
        add(note);
        len = 1000;
      }
      sprintf(line, "VAR %s=", text);
      memcpy(line + strlen(line), buffer + r->V.ExpressionValue.oExpressionValue, len);
      line[strlen("VAR =") + strlen(text) + len] = '\0';
      add(line);
      any = 1;
    }
  }
  if (!any) {
    char line[200];
    sprintf(line, "VAR %s=?no value", name);
    add(line);
  }
}

/* No more stops recorded for it; a failed removal is noted (the program then still stops there, unrecorded) */
static void removeBreak(Break *br) {
  ErrorCode err;
  long column = 1, stmt = 0;
  char line[100];
  clearError(&err);
  QteRemoveBreakpoint(&br->viewId, &br->line, &column, &stmt, (_TE_ERROR_CODE_T *) &err);
  br->active = 0;
  if (err.bytesAvailable > 0) {
    sprintf(line, "breakpoint %.10s %ld not removed after %d stops", br->module, br->line, br->hits);
    noteError(line, &err);
  } else {
    sprintf(line, "NOTE breakpoint %.10s %ld removed after %d stops", br->module, br->line, br->hits);
    add(line);
  }
}

/* The system calls this at a breakpoint (or an unmonitored exception): record, maybe remove it, return */
static void stopped(char *pgm, char *type, char *module, char *reason, long *stmts, long *count, char *message) {
  long k;
  if (reason[0] == '1') {  /* unmonitored exception: noted; the job handles it as usual once this returns */
    char line[100];
    sprintf(line, "NOTE exception %.7s in %.10s", message + 4, module);
    add(line);
  }
  if (reason[1] != '1') return;
  for (k = 0; k < *count && k < 3; k++) {
    int b, v;
    for (b = 0; b < breakCount; b++) {
      Break *br = &breaks[b];
      View *view = NULL;
      if (!br->active || br->stmt != stmts[k] || memcmp(br->module, module, 10) != 0) continue;
      for (v = 0; v < viewCount; v++) if (views[v].viewId == br->viewId) view = &views[v];
      br->hits++;
      totalStops++;
      {
        char line[100];
        sprintf(line, "STOP %.10s %ld %d", br->module, br->line, br->hits);
        add(line);
      }
      if (view) for (v = 0; v < varCount; v++) evaluate(view, vars[v]);
      if (totalStops >= 500) {  /* enough for one call: no breakpoint stops it again */
        int all;
        for (all = 0; all < breakCount; all++) if (breaks[all].active) removeBreak(&breaks[all]);
      } else if (br->hits >= maxHits) {  /* enough here: the program runs on without stopping at this line */
        removeBreak(br);
      }
    }
  }
}

/* "E": end debug, hand back what was recorded */
static void end(char *out) {
  ErrorCode err;
  clearError(&err);
  QteEndSourceDebug((_TE_ERROR_CODE_T *) &err);
  if (err.bytesAvailable > 0) noteError("QteEndSourceDebug", &err);
  memset(out, ' ', RESULT_SIZE);
  memcpy(out, result, resultLen);
}

int main(int argc, char *argv[]) {
  if (argc == 8) {  /* program-stop handler */
    stopped(argv[1], argv[2], argv[3], argv[4], (long *) argv[5], (long *) argv[6], argv[7]);
    return 0;
  }
  if (argc >= 7 && argv[1][0] == 'S') start(argv[2], argv[3], argv[4], argv[5], argv[6]);
  else if (argc >= 3 && argv[1][0] == 'E') end(argv[2]);
  return 0;
}
