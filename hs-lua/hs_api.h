/* Table layout of the generated Hyperspace API description (hs-lua/gen/hs_api.c). */
#ifndef HS_API_H
#define HS_API_H

typedef struct { const char *name; const char *str; int isClass; } HsType;        /* str: SWIG type string, "CrewMember *" */
/* kind: 1 number, 2 boolean, 3 string, 4 pointer to object, 5 embedded member (reference), 6 value copy (owned)
 * setCheck: -1 immutable, else HS_CHECK_*; objType: static type of object results / setter values */
typedef struct {
  const char *name; int kind; int objType; int setCheck; int setUnsigned; int setType;
  const char *swigName; const char *setCtype; const char *setFailName;
} HsAttr;
/* A normal wrapper: argument checks hs_args[aoff..aoff+nargs), arity nmin..nmax.
 * A dispatcher (isDispatch): candidates hs_cands[aoff..aoff+nargs), error = message when none matches. */
typedef struct {
  const char *label; const char *swigName; const char *failName; int isDispatch;
  int aoff; int nargs; int nmin; int nmax; int vecGet;
} HsMethod;
typedef struct { int i; int check; int typeId; int nonull; int isUnsigned; const char *ctype; const char *failName; } HsArg;
typedef struct { int argc; int aoff; int n; int target; } HsCand;
typedef struct { const char *name; char kind; int id; } HsMember;                 /* kind 'a' attribute, 'm' method, 'c' class */
typedef struct { const char *name; int isString; long long n; const char *s; } HsConst;
typedef struct {
  int baseOff; int attrOff, nattr; int methOff, nmeth; int metaOff, nmeta; int sattrOff, nsattr; int smethOff, nsmeth;
  int constOff, nconst; int ctor; int hasTable; int hasDtor;
} HsClass;
typedef struct {
  const char *name; int attrOff, nattr; int methOff, nmeth; int constOff, nconst; int classOff, nclass; const char *luacode;
} HsNamespace;

enum { HS_CHECK_ANY = 0, HS_CHECK_NUM = 1, HS_CHECK_BOOL = 2, HS_CHECK_NSTR = 3, HS_CHECK_STR = 4, HS_CHECK_PTR = 5,
       HS_CHECK_UD = 6, HS_CHECK_FN = 7, HS_CHECK_TAB = 8 };
enum { HS_K_NUM = 1, HS_K_BOOL = 2, HS_K_STR = 3, HS_K_PTR = 4, HS_K_MEM = 5, HS_K_VAL = 6 };

extern const int hs_ntypes, hs_nattrs, hs_nmethods, hs_nnamespaces;
extern const HsType hs_types[];
extern const short hs_bases[];
extern const HsAttr hs_attrs[];
extern const HsMethod hs_methods[];
extern const HsArg hs_args[];
extern const HsCand hs_cands[];
extern const HsClass hs_classes[];
extern const HsMember hs_members[];
extern const HsConst hs_consts[];
extern const HsNamespace hs_namespaces[];
extern const short hs_sclasses[];
extern const short hs_sclass_off[];

#endif
