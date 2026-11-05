SellPlugin 2.0  (sell + economia de preços + valor ao passar o mouse, tudo em 1 plugin)

Compilar: mvn clean package -> target/SellPlugin-2.0.jar -> plugins/
NÃO precisa de ProtocolLib nem PacketEvents instalados (já vão dentro do .jar).
Dinheiro: Vault, ou "fallback-command" no config.yml.

/sell                          abre o menu (vende com o botão verde ou ao fechar; toca um som)
/sell setprice <item|hand> <preço>   edita preço (OP)  | /sell price <item|hand>
/sell reload                   recarrega o config
/valoritem                     liga/desliga o valor no tooltip só para você
