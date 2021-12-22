#/bin/sh 

if [ "$1" = "c" ]; then 
	rm -rf *.class
	exit
fi

rm -rf *.class && javac -d . src/ChatClient.java  && javac -d . src/ChatServer.java
 
if [ "$#" -eq 2 ]; then
	if [ "$1" = "run" ]; then 
		i=0
		# launch server 
		java ChatServer 8000 2>&1  logServer.txt & disown

		# launch n clients
		while [ "$i" -lt "$2" ]; do
			java ChatClient localhost 8000 & disown
			i=$((i + 1))
		done


	fi
fi
