/*
 * rime-daemon: Termux-side Rime engine bridge for TermuxBridge IME.
 * Listens on the abstract unix socket "lll.bridge.termux.rime"; one line-JSON
 * request -> one line-JSON response. Peers are uid-gated (bridge app, Termux,
 * shell, root) - same policy as the main bridge.
 * The keyboard never links librime (app is sandboxed from Termux libs);
 * this process owns the engine, session and dictionaries.
 *
 * Ops:
 *   {"op":"key","key":110,"mask":0}     process a keysym (X11 keysym: ascii==code)
 *   {"op":"select","index":0}           pick candidate on current page
 *   {"op":"commit"}                     commit composition
 *   {"op":"clear"}                      clear composition
 *   {"op":"page","dir":1}               change_page(1=next,0=prev)
 *   {"op":"option","name":"ascii_mode","value":1}
 *   {"op":"schema","id":"wanxiang"}
 *   {"op":"schemas"}                    list schemas + current + ascii_mode
 *   {"op":"status"}                     same payload as schemas
 *   {"op":"deploy"}                     full re-deploy (blocking)
 * Response always carries a full context snapshot after the mutation:
 *   {"ok":true,...,"preedit":"ni","committed":"","highlight":0,
 *    "page":0,"last":true,"cands":[{"t":"你","c":"comment"},...]}
 * Errors: {"ok":false,"error":"..."}
 */
#include <rime_api.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>

#define SOCKNAME "lll.bridge.termux.rime"
#define REQMAX 65536
#define RSPMAX (2 * 1024 * 1024)
#define CANDMAX 48

static RimeApi* rime;
static RimeSessionId session;

/* ---------- minimal JSON out ---------- */
static char rsp[RSPMAX];
static size_t rl;
static void ro(void) { rl = 0; }
static void rs(const char* s) { size_t n = strlen(s); if (rl + n < RSPMAX) { memcpy(rsp + rl, s, n); rl += n; } }
static void rc1(char c) { if (rl + 1 < RSPMAX) rsp[rl++] = c; }
static void rnum(long v) { char b[32]; snprintf(b, sizeof b, "%ld", v); rs(b); }
static void rjson_str(const char* s) {
    rc1('"');
    for (const unsigned char* p = (const unsigned char*)s; p && *p; p++) {
        if (*p == '"' || *p == '\\') { rc1('\\'); rc1(*p); }
        else if (*p == '\n') { rs("\\n"); }
        else if (*p == '\r') { rs("\\r"); }
        else if (*p == '\t') { rs("\\t"); }
        else if (*p < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", *p); rs(b); }
        else rc1((char)*p);
    }
    rc1('"');
}
static void rkey(const char* k) { rjson_str(k); rc1(':'); }

/* ---------- minimal flat-JSON in (string/int values only) ---------- */
static const char* jfind(const char* json, const char* key) {
    size_t kl = strlen(key);
    const char* p = json;
    while ((p = strstr(p, key)) != NULL) {
        if (p > json && p[-1] == '"') {
            const char* v = p + kl;
            if (v[0] == '"' && v[1] == ':') return v + 2;
        }
        p += kl;
    }
    return NULL;
}
static int jstr(const char* json, const char* key, char* out, size_t outn) {
    const char* v = jfind(json, key);
    if (!v || *v != '"') return 0;
    v++;
    size_t i = 0;
    while (*v && *v != '"' && i + 1 < outn) {
        if (*v == '\\' && v[1]) v++;
        out[i++] = *v++;
    }
    out[i] = 0;
    return 1;
}
static int jint(const char* json, const char* key, long* out) {
    const char* v = jfind(json, key);
    if (!v) return 0;
    if (*v == '"') v++;
    char* end; long x = strtol(v, &end, 10);
    if (end == v) return 0;
    *out = x; return 1;
}

/* ---------- context snapshot ---------- */
static void emit_context(void) {
    RIME_STRUCT(RimeContext, ctx);
    Bool ctx_ok = rime->get_context(session, &ctx);
    rs(",\"ctx_ok\":"); rnum(ctx_ok ? 1 : 0);
    rs(",\"preedit\":"); rjson_str(ctx.composition.preedit ? ctx.composition.preedit : "");
    rs(",\"preview\":"); rjson_str(ctx.commit_text_preview ? ctx.commit_text_preview : "");
    rs(",\"highlight\":"); rnum((long)ctx.menu.highlighted_candidate_index);
    rs(",\"page\":"); rnum((long)ctx.menu.page_no);
    rs(",\"last\":"); rnum(ctx.menu.is_last_page ? 1 : 0);
    rs(",\"cands\":[");
    int n = (int)ctx.menu.num_candidates; if (n > CANDMAX) n = CANDMAX;
    for (int i = 0; i < n; i++) {
        if (i) rc1(',');
        rc1('{');
        rkey("t"); rjson_str(ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "");
        rs(",\"c\":"); rjson_str(ctx.menu.candidates[i].comment ? ctx.menu.candidates[i].comment : "");
        rc1('}');
    }
    rs("]");
    rime->free_context(&ctx);
}

static void reply_ok(void) { rs("{\"ok\":true"); emit_context(); rc1('}'); rc1('\n'); }
static void reply_err(const char* msg) { rs("{\"ok\":false,\"error\":"); rjson_str(msg); rs("}\n"); }

static void do_key(long key, long mask) {
    Bool processed = rime->process_key(session, (int)key, (int)mask);
    rs("{\"ok\":true,\"processed\":"); rnum(processed ? 1 : 0);
    /* not-processed keys may produce a commit (e.g. ascii in ascii_mode) */
    RIME_STRUCT(RimeCommit, commit);
    rs(",\"committed\":");
    if (rime->get_commit(session, &commit) && commit.text) { rjson_str(commit.text); rime->free_commit(&commit); }
    else rjson_str("");
    emit_context();
    rc1('}'); rc1('\n');
}

static void do_select(long idx) {
    if (!rime->select_candidate_on_current_page(session, (size_t)idx)) { reply_err("select failed"); return; }
    RIME_STRUCT(RimeCommit, commit);
    rs("{\"ok\":true,\"committed\":");
    if (rime->get_commit(session, &commit) && commit.text) { rjson_str(commit.text); rime->free_commit(&commit); }
    else rjson_str("");
    emit_context();
    rc1('}'); rc1('\n');
}

static void do_commit(void) {
    RIME_STRUCT(RimeCommit, commit);
    rs("{\"ok\":true,\"committed\":");
    if (rime->commit_composition(session) && rime->get_commit(session, &commit) && commit.text) {
        rjson_str(commit.text);
        rime->free_commit(&commit);
    } else rjson_str("");
    emit_context();
    rc1('}'); rc1('\n');
}

static void do_schemas(void) {
    RimeSchemaList list;
    char cur[128] = "";
    rs("{\"ok\":true,\"current\":");
    if (rime->get_current_schema(session, cur, sizeof cur)) rjson_str(cur);
    else rjson_str("");
    rs(",");
    if (rime->get_schema_list(&list)) {
        rs("\"schemas\":[");
        for (size_t i = 0; i < list.size; i++) {
            if (i) rc1(',');
            rc1('{'); rkey("id"); rjson_str(list.list[i].schema_id);
            rs(",\"name\":"); rjson_str(list.list[i].name ? list.list[i].name : ""); rc1('}');
        }
        rime->free_schema_list(&list);
        rs("]");
    } else rs("\"schemas\":[]");
    rs(",\"ascii\":"); rnum(rime->get_option(session, "ascii_mode") ? 1 : 0);
    rc1('}'); rc1('\n');
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    rime = rime_get_api();
    if (!rime) { fprintf(stderr, "librime not available\n"); return 1; }

    RIME_STRUCT(RimeTraits, traits);
    traits.app_name = "rime.tbridge.daemon";
    traits.distribution_name = "TermuxBridge";
    traits.distribution_code_name = "rime-daemon";
    traits.distribution_version = "0.1";
    traits.min_log_level = 0;
    const char* home = getenv("HOME");
    static char ud[512], sd[512];
    snprintf(ud, sizeof ud, "%s/rime/wanxiang", home ? home : "/data/data/com.termux/files/home");
    snprintf(sd, sizeof sd, "%s/share/rime-data", getenv("PREFIX") ? getenv("PREFIX") : "/data/data/com.termux/files/usr");
    traits.user_data_dir = ud;
    traits.shared_data_dir = sd;
    static char st[512]; snprintf(st, sizeof st, "%s/build", ud); traits.staging_dir = st;
    rime->setup(&traits);
    rime->initialize(&traits);
    rime->start_maintenance(False);
    rime->join_maintenance_thread();

    session = rime->create_session();
    if (!session) { fprintf(stderr, "create_session failed\n"); return 1; }

    /* resolve the bridge app's uid once; unknown uid -> nobody may connect */
    int gate_uid = -1;
    {
        FILE* fp = popen("pm list packages -U lll.bridge.termux 2>/dev/null", "r");
        if (fp) {
            char ln[256];
            while (fgets(ln, sizeof ln, fp)) {
                char* u = strstr(ln, "uid:");
                if (u) { gate_uid = atoi(u + 4); break; }
            }
            pclose(fp);
        }
    }
    if (gate_uid <= 0) { fprintf(stderr, "rime-daemon: cannot resolve bridge uid; refusing to listen\n"); return 1; }

    int srv = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';                       /* abstract namespace */
    size_t nlen = strlen(SOCKNAME);                 /* no trailing NUL: name length is part of the address */
    memcpy(addr.sun_path + 1, SOCKNAME, nlen);
    socklen_t alen = offsetof(struct sockaddr_un, sun_path) + 1 + nlen;
    if (bind(srv, (struct sockaddr*)&addr, alen) != 0) { perror("bind"); return 1; }
    listen(srv, 4);
    fprintf(stderr, "rime-daemon: listening on unix:@%s user=%s gate_uid=%d\n", SOCKNAME, ud, gate_uid);

    static char req[REQMAX];
    for (;;) {
        int fd = accept(srv, NULL, NULL);
        if (fd < 0) continue;
        struct ucred cred;
        socklen_t clen = sizeof cred;
        int uid_ok = 0;
        if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &clen) == 0) {
            if (cred.uid == 0 || cred.uid == 2000
                || cred.uid == (uid_t)gate_uid || cred.uid == getuid()) uid_ok = 1;
        }
        if (!uid_ok) { close(fd); continue; }
        size_t n = 0;
        while (n + 1 < REQMAX) {
            char c;
            ssize_t r = recv(fd, &c, 1, 0);
            if (r <= 0) break;
            if (c == '\n') break;
            req[n++] = c;
        }
        req[n] = 0;
        if (n > 0) {
            char op[32] = "", id[256] = "", name[128] = "";
            long key = 0, mask = 0, idx = 0, dir = 0, val = 0;
            jstr(req, "op", op, sizeof op);
            if (!strcmp(op, "key")) {
                jint(req, "key", &key); jint(req, "mask", &mask);
                do_key(key, mask);
            } else if (!strcmp(op, "select")) {
                jint(req, "index", &idx); do_select(idx);
            } else if (!strcmp(op, "commit")) {
                do_commit();
            } else if (!strcmp(op, "clear")) {
                rime->clear_composition(session);
                reply_ok();
            } else if (!strcmp(op, "page")) {
                jint(req, "dir", &dir);
                rime->change_page(session, dir == 1 ? False : True);
                reply_ok();
            } else if (!strcmp(op, "option")) {
                jstr(req, "name", name, sizeof name); jint(req, "value", &val);
                if (name[0]) rime->set_option(session, name, val ? True : False);
                reply_ok();
            } else if (!strcmp(op, "schema")) {
                jstr(req, "id", id, sizeof id);
                Bool ok = id[0] ? rime->select_schema(session, id) : False;
                if (ok) reply_ok(); else reply_err("schema not found");
            } else if (!strcmp(op, "schemas") || !strcmp(op, "status")) {
                do_schemas();
            } else if (!strcmp(op, "deploy")) {
                rime->start_maintenance(True);
                rime->join_maintenance_thread();
                reply_ok();
            } else if (!strcmp(op, "ping")) {
                rs("{\"ok\":true,\"engine\":\"rime\"}\n");
            } else {
                reply_err("unknown op");
            }
            if (rl > 0) {
                size_t off = 0;
                while (off < rl) {
                    ssize_t w = send(fd, rsp + off, rl - off, 0);
                    if (w <= 0) break;
                    off += (size_t)w;
                }
            }
            rl = 0;
        }
        close(fd);
    }
    return 0;
}
