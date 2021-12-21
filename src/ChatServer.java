import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

enum STATE {
	INIT,
	OUTSIDE,
	INSIDE
}

class User {
	String Username;
	SocketChannel sc;
	String Message;
	// String State;
	STATE State;
	String Room;

	User(String Username, SocketChannel sc) {
		this.Username = Username;
		this.sc = sc;
		this.Message = "";
		this.State = STATE.INIT;
		this.Room = null;
	}
}

class Room {
	String Identifier;
	Set<User> currentUsers;

	Room(String Name) {
		this.Identifier = Name;
		this.currentUsers = new HashSet<User>();
	}
}

public class ChatServer {
	// A pre-allocated buffer for the received data
	static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

	// Decoder for incoming text -- assume UTF-8
	static private final Charset charset = Charset.forName("UTF8");
	static private final CharsetDecoder decoder = charset.newDecoder();

	static private final Map<String, User> ListUsers = new HashMap<>();
	static private final Map<String, Room> ListRooms = new HashMap<>();

	static public void main(String args[]) throws Exception {

		// Parse port from command line
		int port = Integer.parseInt(args[0]);

		try {
			// Instead of creating a ServerSocket, create a ServerSocketChannel
			ServerSocketChannel ssc = ServerSocketChannel.open();

			// Set it to non-blocking, so we can use select
			ssc.configureBlocking(false);

			// Get the Socket connected to this channel, and bind it to the
			// listening port
			ServerSocket ss = ssc.socket();
			InetSocketAddress isa = new InetSocketAddress(port);
			ss.bind(isa);

			// Create a new Selector for selecting
			Selector selector = Selector.open();

			// Register the ServerSocketChannel, so we can listen for incoming
			// connections
			ssc.register(selector, SelectionKey.OP_ACCEPT);
			System.out.println("Listening on port " + port);

			while (true) {
				// See if we've had any activity -- either an incoming connection,
				// or incoming data on an existing connection
				int num = selector.select();

				// If we don't have any activity, loop around and wait again
				if (num == 0) {
					continue;
				}

				// Get the keys corresponding to the activity that has been
				// detected, and process them one by one
				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> it = keys.iterator();
				while (it.hasNext()) {
					// Get a key representing one of bits of I/O activity
					SelectionKey key = it.next();

					// What kind of activity is it?
					if (key.isAcceptable()) {

						// It's an incoming connection. Register this socket with
						// the Selector so we can listen for input on it
						Socket s = ss.accept();
						System.out.println("Got connection from " + s);

						// Make sure to make it non-blocking, so we can use a selector
						// on it.
						SocketChannel sc = s.getChannel();
						sc.configureBlocking(false);

						// Register it with the selector, for reading
						sc.register(selector, SelectionKey.OP_READ, new User(null, sc));

					} else if (key.isReadable()) {

						SocketChannel sc = null;
						System.out.println("Got channel for reading");

						try {

							// It's incoming data on a connection -- process it
							sc = (SocketChannel) key.channel();
							boolean ok = processInput(sc, key);

							// If the connection is dead, remove it from the selector
							// and close it
							if (!ok) {
								removeUser(key);
								key.cancel();

								Socket s = null;
								try {
									s = sc.socket();
									System.out.println("Closing connection to " + s);
									s.close();
								} catch (IOException ie) {
									System.err.println("Error closing socket " + s + ": " + ie);
								}
							}

						} catch (IOException ie) {

							// On exception, remove this channel from the selector
							removeUser(key);
							key.cancel();

							try {
								sc.close();
							} catch (IOException ie2) {
								System.out.println(ie2);
							}

							System.out.println("Closed " + sc);
						}
					}
				}

				// We remove the selected keys, because we've dealt with them.
				keys.clear();
			}
		} catch (IOException ie) {
			System.err.println(ie);
		}
	}

	// Just read the message from the socket and send it to stdout
	static private boolean processInput(SocketChannel sc, SelectionKey key) throws IOException {
		// Read the message to the buffer
		buffer.clear();
		sc.read(buffer);
		buffer.flip();

		// If no data, close the connection
		if (buffer.limit() == 0) {
			return false;
		}

		// Decode and print the message to stdout
		String message = decoder.decode(buffer).toString();

		User current = (User) key.attachment();

		if (message.charAt(message.length() - 1) != '\n') {
			current.Message += message;
			return true;
		}

		parseMessage(current.Message + message, sc, key);
		current.Message = "";

		return true;
	}

	static private void parseMessage(String Message, SocketChannel sc, SelectionKey key) throws IOException {

		// Message is a command
		if (Message.charAt(0) == '/' && Message.charAt(1) != '/') {

			// Split the message at the first space char
			String MessageSplited[] = Message.split(" ", 2);

			switch (Message) {
				case "/nick":
					nick(MessageSplited[1], sc, key);
					break;
				case "/join":
					join(MessageSplited[1], sc, key);
					break;
				case "/priv":
					priv(MessageSplited[1], sc, key);
					break;
				case "/leave":
					leave(sc, key);
					break;
				case "/bye":
					bye(sc, key);
					break;
				default:
					sendMessage(sc, "ERROR" + System.lineSeparator());
			}
		}

		// Is a normal message let's send it to the group
		else {
			User sender = (User) key.attachment();

			if (Message.charAt(0) == '/' && Message.charAt(1) == '/')
				Message = Message.substring(1); // remove the escaped '/'

			if (sender.State == STATE.INSIDE) {
				String msg = "MESSAGE " + sender.Username + " " + Message;
				notifyRoom(sender.Room, sender.Username, msg);
			}
		}
	}

	static private void removeUser(SelectionKey key) throws IOException {
		if (key.attachment() != null) {

			User userToRemove = (User) key.attachment();

			if (userToRemove.State == STATE.INIT) {
				ListUsers.remove(userToRemove.Username);
			}

			else if (userToRemove.State == STATE.INSIDE) {
				ListRooms.get(userToRemove.Room).currentUsers.remove(userToRemove);
				ListUsers.remove(userToRemove.Username);

				String exitMessage = "LEFT" + userToRemove.Username + System.lineSeparator();
				notifyRoom(userToRemove.Room, userToRemove.Username, exitMessage);
			}
		}
	}

	static private void notifyRoom(String Room, String User, String Message) throws IOException {
		for (User tmp : ListRooms.get(Room).currentUsers) {
			if (tmp.Username != User) {
				sendMessage(ListUsers.get(tmp.Username).sc, Message);
			}
		}
	}

	static private void sendMessage(SocketChannel sc, String Message) throws IOException {
		CharBuffer cb = CharBuffer.wrap(Message.toCharArray());
		ByteBuffer bb = charset.encode(cb);
		sc.write(bb);
	}

	static private void nick(String NewUserName, SocketChannel sc, SelectionKey key) throws IOException {

		// UserName already used
		if (ListUsers.containsKey(NewUserName)) {
			sendMessage(sc, "ERROR" + System.lineSeparator());
			return;
		}

		User currentUser = (User) key.attachment();

		String oldUserName = currentUser.Username;

		currentUser.Username = NewUserName;

		if (currentUser.State == STATE.INSIDE) {
			String message = "NEWNICK " + oldUserName + " " + NewUserName + System.lineSeparator();
			notifyRoom(currentUser.Room, currentUser.Username, message);
		}

		sendMessage(sc, "OK" + System.lineSeparator());
	}

	// TODO
	static private void join(String RoomName, SocketChannel sc, SelectionKey key) throws IOException {
		User userWantJoin = (User) key.attachment();

		if (userWantJoin.State == STATE.INIT) {
			sendMessage(sc, "ERROR" + System.lineSeparator());
			return;
		}

		else {

			// Room doesn't exist let's create one
			if (!ListRooms.containsKey(RoomName)) {
				ListRooms.put(RoomName, new Room(RoomName));
			}

			if (userWantJoin.State == STATE.OUTSIDE) {

				ListRooms.get(RoomName).currentUsers.add(userWantJoin);
				userWantJoin.Room = RoomName;

				String message = "JOINED " + userWantJoin.Username + System.lineSeparator();
				notifyRoom(RoomName, userWantJoin.Username, message);

				userWantJoin.State = STATE.INSIDE;
			}

			else if (userWantJoin.State == STATE.INSIDE) {
				ListRooms.get(userWantJoin.Room).currentUsers.remove(userWantJoin);

				String message = "LEFT " + userWantJoin.Username + System.lineSeparator();
				notifyRoom(userWantJoin.Room, userWantJoin.Username , message);

				ListRooms.get(RoomName).currentUsers.add(userWantJoin);
				userWantJoin.Room = RoomName;

				message = "JOINED " + userWantJoin.Username + System.lineSeparator();
				notifyRoom(userWantJoin.Room, userWantJoin.Username , message);

			}

			sendMessage(sc, "OK" + System.lineSeparator());
		}
	}

	// TODO
	static private void leave(SocketChannel sc, SelectionKey key) throws IOException {
	}

	// TODO
	static private void priv(String Message, SocketChannel sc, SelectionKey key) throws IOException {
	}

	// TODO
	static private void bye(SocketChannel sc, SelectionKey key) throws IOException {
	}

}