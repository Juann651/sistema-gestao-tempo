package br.sgt;

public class ErroSupabase extends RuntimeException {
    public final int status;
    public ErroSupabase(int status, String mensagem) { super(mensagem); this.status = status; }
}
