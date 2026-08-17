# Password Safe 0.10

Prima base Android del gestore password.

## Funzioni incluse

- configurazione PIN obbligatorio di 6 cifre;
- due domande di sicurezza personalizzabili;
- risposte e PIN protetti con PBKDF2-HMAC-SHA256 e salt casuale;
- accesso con impronta digitale oppure PIN;
- recupero accesso e creazione di un nuovo PIN;
- conteggio dei tentativi PIN errati;
- schermata iniziale della cassaforte.
- archivio locale cifrato AES-GCM tramite Android Keystore;
- creazione, modifica ed eliminazione degli account;
- ricerca e categorie;
- generatore di password da 20 caratteri;
- copia negli appunti con cancellazione automatica dopo 30 secondi;
- backup cifrato e ripristino tramite il selettore file di Google Drive.
- menu di creazione con tre tipi: Account, PIN e Login.
- nuova home colorata con categorie, testata illustrata e navigazione inferiore.

## Compilazione automatica con GitHub

Il workflow `.github/workflows/build-apk.yml` compila automaticamente l'APK a ogni aggiornamento del ramo `main`.

1. Caricare il progetto in un repository GitHub.
2. Aprire la scheda **Actions**.
3. Aprire **Compila APK**.
4. Al termine scaricare l'artifact **PasswordSafe-v0.1-apk**.

Non è necessario installare Android Studio.

## Aggiornamento da Termux

Dentro la cartella del progetto:

```bash
git pull
```

Per caricare modifiche preparate in uno ZIP:

```bash
unzip -o PasswordSafe_v0_2_github.zip -d PasswordSafe
cd PasswordSafe
git add .
git commit -m "Aggiornamento Password Safe"
git push
```

Questa è una versione iniziale. Gli account cifrati e il backup Google Drive verranno aggiunti nelle prossime versioni.
