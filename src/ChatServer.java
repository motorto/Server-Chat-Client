import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

class User {
	String Username;
	SocketChannel sc;
	String Message;
	String State;
	String Room;

	User(String Username, SocketChannel sc) {
		this.Username = Username;
		this.sc = sc;
		this.Message = "";
		this.State = "init";
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

	static private final Map<String,User> ListUsers = new HashMap<>();
	static private final Map<String,Room> ListRooms = new HashMap<>();

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
						sc.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new User(null, sc));

					} else if (key.isReadable()) {

						SocketChannel sc = null;
						System.out.println("Got channel for reading");

						try {

							// It's incoming data on a connection -- process it
							sc = (SocketChannel) key.channel();
							boolean ok = processInput(sc);

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
	static private boolean processInput(SocketChannel sc) throws IOException {
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
		System.out.println(message);

		ByteBuffer out = ByteBuffer.wrap((message).getBytes());
		while (out.hasRemaining())
			sc.write(out);
		System.out.println("Sent");
		return true;
	}

	static private void removeUser(SelectionKey key) throws IOException {
		if (key.attachment() != null) {

			User userToRemove = (User) key.attachment();

			if (userToRemove.State.equals("INIT")) {
				ListUsers.remove(userToRemove.Username);
			}

			else if (userToRemove.State.equals("INSIDE")) {
				ListRooms.get(userToRemove.Room).currentUsers.remove(userToRemove);
				ListUsers.remove(userToRemove.Username);

				String exitMessage = "LEFT" + userToRemove.Username + System.lineSeparator();
				notifyGroup(userToRemove.Room, userToRemove.Username, exitMessage);
			}
		}
	}

	static private void notifyGroup (String Room , String User, String Message ) throws IOException {
		for (User tmp : ListRooms.get(Room).currentUsers) {
			if (tmp.Username != User) {
				sendMessage(ListUsers.get(tmp.Username).sc, Message );
			}
		}
	}

	static private void sendMessage(SocketChannel sc, String Message) throws IOException {
		CharBuffer cb = CharBuffer.wrap(Message.toCharArray());
		ByteBuffer bb = charset.encode(cb);
		sc.write(bb);
	}


}
