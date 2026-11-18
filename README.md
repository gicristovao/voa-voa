# Voa Voa

App Android para consultar as zonas geográficas de voo de drones (UAS) em Portugal, com base nos dados públicos da ANAC. Substitui a antiga app Voa na Boa.

## Instalar

No telemóvel, abre **[a última versão](https://github.com/gicristovao/voa-voa/releases/latest)** e toca em `voa-voa.apk`.
Na primeira vez, o Android pede autorização para instalar apps desta origem.

## O que faz

- Mapa com as zonas: vermelho (proibido), amarelo (autorização ou condições) e cinzento (sem restrição).
- "Posso voar aqui?": usa o GPS e mostra as zonas, a altitude, as condições e a quem pedir autorização.
- Toca em qualquer ponto do mapa para consultar outro local.
- Inclui as restrições temporárias (NOTAM).
- Funciona sem rede com os últimos dados guardados.

## Como se mantém atualizada

- **Na app:** vai buscar os dados à ANAC sempre que é aberta e a cada 12 horas em segundo plano.
- **Cópia de segurança:** a tarefa automática `Atualizar dados ANAC` (separador *Actions*) descarrega os dados duas vezes por dia para `data/zones.json`. A app usa esta cópia quando a ANAC não responde.
- **Nova versão da app:** cada alteração ao código gera uma nova versão em *Releases*.

## Aviso

Não é uma app oficial. Confirma sempre as restrições em [dnt.anac.pt](https://dnt.anac.pt/) antes de voar.

Fonte dos dados: ANAC, formato ED-318. Mapa © colaboradores do OpenStreetMap.
