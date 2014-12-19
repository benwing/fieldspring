/**
 * 
 */
package opennlp.fieldspring.tr.text.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Vector;

import javax.xml.stream.XMLStreamException;

import opennlp.fieldspring.tr.text.Document;
import opennlp.fieldspring.tr.text.DocumentSource;
import opennlp.fieldspring.tr.text.Token;
import opennlp.fieldspring.tr.text.prep.SentenceDivider;
import opennlp.fieldspring.tr.text.prep.Tokenizer;
import opennlp.fieldspring.tr.util.IOUtil;

/**
 * @author abhimanu kumar
 *
 */
public class PlainTextDirSource extends DocumentSource {

	private final Tokenizer tokenizer;
	private final SentenceDivider divider;
	private final Vector<File> files;
	private int currentIdx;
	private PlainTextSource current;


	public PlainTextDirSource(File directory, SentenceDivider divider, Tokenizer tokenizer) {
		this.divider = divider;
		this.tokenizer = tokenizer;
		files=new Vector<File>();
		FilenameFilter filter=new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.endsWith(".txt") || name.endsWith(".txt.gz") || name.endsWith(".txt.bz2");
			}
		};
		listFiles(directory,filter);

		//		    this.files = files == null ? new File[0] : files;
		//		    Arrays.sort(this.files);

		this.currentIdx = 0;
		this.nextFile();
	}

	private void listFiles(File directory, FilenameFilter filter) {
		File[] childrenTextFiles=directory.listFiles(filter);
		for(File file : childrenTextFiles){
			if(file!=null && !file.isDirectory())
				files.add(file);
		}
		File[] childrenDir=directory.listFiles();
		for(File file:childrenDir){
			if(file.isDirectory())
				listFiles(file,filter);
		}
		return;
	}

	private void nextFile() {
		try {
			if (this.current != null) {
				this.current.close();
			}
			if (this.currentIdx < this.files.size()) {
                            File currentFile = this.files.get(this.currentIdx);
                            this.current = new PlainTextSource(IOUtil.createBufferedReader(currentFile), this.divider, this.tokenizer, currentFile.getName());
			}
		} catch (IOException e) {
			System.err.println("Error while reading text file "+this.files.get(this.currentIdx).getName());
		} 
	}

	public void close() {
		if (this.current != null) {
			this.current.close();
		}
	}

	public boolean hasNext() {
		if (this.currentIdx < this.files.size()) {
			if (this.current.hasNext()) {
				return true; 
			} else {
				this.currentIdx++;
				this.nextFile();
				return this.hasNext();
			}
		} else {
			return false;
		}
	}

	public Document<Token> next() {
		return this.current.next();
	}

}
